/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.testing

import com.google.common.base.Splitter
import com.google.common.collect.Lists
import groovy.transform.CompileStatic
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import groovy.xml.XmlUtil
import groovyx.net.http.ContentType
import groovyx.net.http.RESTClient
import org.gradle.api.GradleException
import org.gradle.api.internal.tasks.testing.DecoratingTestDescriptor
import org.gradle.api.internal.tasks.testing.DefaultTestClassDescriptor
import org.gradle.api.internal.tasks.testing.DefaultTestMethodDescriptor
import org.gradle.api.internal.tasks.testing.DefaultTestOutputEvent
import org.gradle.api.internal.tasks.testing.DefaultTestSuiteDescriptor
import org.gradle.api.internal.tasks.testing.results.DefaultTestResult
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestOutputEvent
import org.gradle.api.tasks.testing.TestOutputListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.internal.IoActions
import org.gradle.internal.event.ListenerBroadcast

import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Runs each performance test scenario in a dedicated TeamCity job.
 *
 * The test runner is instructed to just write out the list of scenarios
 * to run instead of actually running the tests. Then this list is used
 * to schedule TeamCity jobs for each individual scenario. This task
 * blocks until all the jobs have finished and aggregates their status.
 */
@CompileStatic
class DistributedPerformanceTest extends PerformanceTest {

    @Input @Optional
    String coordinatorBuildId

    @Input @Optional
    String branchName

    @Input
    String buildTypeId

    @Input
    String workerTestTaskName

    @Input
    String teamCityUrl

    @Input
    String teamCityUsername

    @Input
    String teamCityPassword

    @OutputFile
    File scenarioList

    @OutputFile
    File scenarioReport

    RESTClient client

    List<String> scheduledBuilds = Lists.newArrayList()

    List<Object> finishedBuilds = Lists.newArrayList()

    Map<String, List<File>> testResultFilesForBuild = [:]
    private File workerTestResultsTempDir

    private List<TestListener> myTestListeners = []
    private List<TestOutputListener> myTestOutputListeners = []
    ListenerBroadcast<TestListener> myTestListenerBroadcaster
    ListenerBroadcast<TestOutputListener> myTestOutputListenerBroadcaster

    DistributedPerformanceTest() {
        myTestListenerBroadcaster = listenerManager.createAnonymousBroadcaster(TestListener.class)
        myTestOutputListenerBroadcaster = listenerManager.createAnonymousBroadcaster(TestOutputListener.class)
    }

    @Override
    void addTestListener(TestListener listener) {
        if (!listener.getClass().getName().startsWith('org.gradle.api.internal')) {
            myTestListeners.add(listener)
        }
    }

    @Override
    void addTestOutputListener(TestOutputListener listener) {
        if (!listener.getClass().getName().startsWith('org.gradle.api.internal')) {
            myTestOutputListeners.add(listener)
        }
    }

    void setScenarioList(File scenarioList) {
        systemProperty "org.gradle.performance.scenario.list", scenarioList
        this.scenarioList = scenarioList
    }

    @TaskAction
    void executeTests() {
        createWorkerTestResultsTempDir()
        try {
            doExecuteTests()
        } finally {
            releaseListeners()
            cleanTempFiles()
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private void releaseListeners() {
        myTestOutputListeners.clear()
        myTestListeners.clear()
        myTestListenerBroadcaster.removeAll()
        myTestOutputListenerBroadcaster.removeAll()
    }

    private void createWorkerTestResultsTempDir() {
        workerTestResultsTempDir = File.createTempFile("worker-test-results", "")
        workerTestResultsTempDir.delete()
        workerTestResultsTempDir.mkdir()
    }

    private void cleanTempFiles() {
        workerTestResultsTempDir.deleteDir()
    }

    private void doExecuteTests() {
        scenarioList.delete()

        fillScenarioList()

        def scenarios = scenarioList.readLines()
            .collect { line ->
                def parts = Splitter.on(';').split(line).toList()
                new Scenario(id : parts[0], estimatedRuntime: new BigDecimal(parts[1]), templates: parts.subList(2, parts.size()))
            }
            .sort{ -it.estimatedRuntime }
            .takeRight(10)

        createClient()

        def lastChangeId = resolveLastChangeId()
        scenarios.each {
            schedule(it, lastChangeId)
        }

        reAddListeners()

        scheduledBuilds.each {
            join(it)
        }

        writeScenarioReport()

        checkForErrors()
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private void reAddListeners() {
        myTestListenerBroadcaster.addAll(myTestListeners)
        myTestOutputListenerBroadcaster.addAll(myTestOutputListeners)
    }

    private void fillScenarioList() {
        super.executeTests()
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private void schedule(Scenario scenario, String lastChangeId) {
        def buildRequest = """
                <build${branchName ? " branchName=\"${branchName}\"" : ""}>
                    <buildType id="${buildTypeId}"/>
                    <properties>
                        <property name="scenario" value="${scenario.id}"/>
                        <property name="templates" value="${scenario.templates.join(' ')}"/>
                        <property name="baselines" value="${baselines?:'defaults'}"/>
                        <property name="warmups" value="${warmups!=null?:'defaults'}"/>
                        <property name="runs" value="${runs!=null?:'defaults'}"/>
                        <property name="checks" value="${checks?:'all'}"/>
                        <property name="channel" value="${channel?:'commits'}"/>
                    </properties>
                    ${renderLastChange(lastChangeId)}
                </build>
            """
        logger.info("Scheduling $scenario.id, estimated runtime: $scenario.estimatedRuntime, coordinatorBuildId: $coordinatorBuildId, lastChangeId: $lastChangeId, build request: $buildRequest")
        def response = client.post(
            path: "buildQueue",
            requestContentType: ContentType.XML,
            body: buildRequest
        )
        if (!response.success) {
            throw new RuntimeException("Cannot schedule build job. build request: $buildRequest\nresponse: ${xmlToString(response.data)}")
        }
        def workerBuildId = response.data.@id
        def scheduledChangeId = findLastChangeIdInXml(response.data)
        if (lastChangeId && scheduledChangeId != lastChangeId) {
            throw new RuntimeException("The requested change id is different than the actual one. requested change id: $lastChangeId in coordinatorBuildId: $coordinatorBuildId , actual change id: $scheduledChangeId in workerBuildId: $workerBuildId\nresponse: ${xmlToString(response.data)}")
        }
        scheduledBuilds += workerBuildId
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private static String xmlToString(xmlObject) {
        if (xmlObject != null) {
            try {
                return XmlUtil.serialize(xmlObject)
            } catch (e) {
                // ignore errors
            }
        }
        return null
    }

    private String renderLastChange(lastChangeId) {
        if (lastChangeId) {
            return """
                <lastChanges>
                    <change id="$lastChangeId"/>
                </lastChanges>
            """
        } else {
            return ""
        }
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private String resolveLastChangeId() {
        if (coordinatorBuildId) {
            def response = client.get(path: "builds/id:$coordinatorBuildId")
            if (response.success) {
                return findLastChangeIdInXml(response.data)
            }
        }
        return null
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private String findLastChangeIdInXml(xmlroot) {
        xmlroot.lastChanges.change[0].@id.text()
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private void join(String jobId) {
        def finished = false
        def response
        while (!finished) {
            response = client.get(path: "builds/id:$jobId")
            finished = response.data.@state == "finished"
            if (!finished) {
                sleep(TimeUnit.MINUTES.toMillis(1))
            }
        }
        finishedBuilds += response.data

        try {
            def results = fetchTestResults(jobId, response.data)
            testResultFilesForBuild.put(jobId, results)
            fireTestListener(results)
        } catch (e) {
            e.printStackTrace(System.err)
        }
    }

    public static int id = 0

    @TypeChecked(TypeCheckingMode.SKIP)
    private void fireTestListener(List<File> results) {
        def xmlFiles = results.findAll { it.name.endsWith('.xml') }
        def rootSuite = new DefaultTestSuiteDescriptor(DistributedPerformanceTest.id++, "rootSuite" + DistributedPerformanceTest.id)
        def workerSuite = new DecoratingTestDescriptor(new DefaultTestSuiteDescriptor(DistributedPerformanceTest.id++, "workerSuite" + DistributedPerformanceTest.id), rootSuite)
        def testListener = myTestListenerBroadcaster.getSource()
        testListener.beforeSuite(rootSuite)
        testListener.beforeSuite(workerSuite)
        xmlFiles.each {
            def testResult = new XmlSlurper().parse(it)
            def suiteName = testResult.@name.text()
            println "suiteName: ${suiteName}"
            def testSuiteDescriptor = new DecoratingTestDescriptor(new DefaultTestClassDescriptor(DistributedPerformanceTest.id++, suiteName), workerSuite)
            testListener.beforeSuite(testSuiteDescriptor)
            testResult.testCase.each { testCase ->
                def testCaseClassName = testCase.@classname.text()
                def testMethodName = testCase.@name.text()
                println "testCaseClassName: ${testCaseClassName}"
                println "testMethodName: ${testMethodName}"
                def testCaseDescriptor =  new DecoratingTestDescriptor(new DefaultTestMethodDescriptor(DistributedPerformanceTest.id++, testCaseClassName, testMethodName), testSuiteDescriptor)
                def source = testListener
                def skipped = testCase.skipped
                def failure = testCase.failure

                if (failure) {
                    source.beforeTest(testCaseDescriptor)
                    source.afterTest(testCaseDescriptor, new DefaultTestResult(TestResult.ResultType.FAILURE, 0, 0, 1, 0, 1, []))
                } else if (!skipped) {
                    source.beforeTest(testCaseDescriptor)
                    source.afterTest(testCaseDescriptor, new DefaultTestResult(TestResult.ResultType.SUCCESS, 0, 0, 1, 1, 0, []))
                }
            }
//            try {
////                def systemOut = testResult."system-out".text()
////                myTestOutputListenerBroadcaster.getSource().onOutput(testSuiteDescriptor, new DefaultTestOutputEvent(TestOutputEvent.Destination.StdOut, systemOut))
//                def systemErr = testResult."system-err".text()
//                myTestOutputListenerBroadcaster.getSource().onOutput(testSuiteDescriptor, new DefaultTestOutputEvent(TestOutputEvent.Destination.StdErr, systemErr))
//            } catch (Exception e) {
//                e.printStackTrace()
//            }
            testListener.afterSuite(testSuiteDescriptor, new DefaultTestResult(TestResult.ResultType.SUCCESS, 0, 0, 0, 0, 0, []))
        }
        testListener.afterSuite(workerSuite, new DefaultTestResult(TestResult.ResultType.SUCCESS, 0, 0, 0, 0, 0, []))
        testListener.afterSuite(rootSuite, new DefaultTestResult(TestResult.ResultType.SUCCESS, 0, 0, 0, 0, 0, []))
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private def fetchTestResults(String jobId, buildData) {
        def unzippedFiles = []
        def artifactsUri = buildData?.artifacts?.@href?.text()
        if (artifactsUri) {
            def resultArtifacts = client.get(path: "${artifactsUri}/results/${project.name}/build/")
            if (resultArtifacts.success) {
                def zipName = "test-results-${workerTestTaskName}.zip".toString()
                def fileNode = resultArtifacts.data.file.find {
                    it.@name.text() == zipName
                }
                if (fileNode) {
                    def resultsDirectory = new File(workerTestResultsTempDir, jobId)
                    def contentUri = fileNode.content.@href.text()
                    client.get(path: contentUri, contentType: ContentType.BINARY) {
                        resp, inputStream ->
                            unzippedFiles = unzipToDirectory(inputStream, resultsDirectory)
                    }
                }
            }
        }
        unzippedFiles
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    def unzipToDirectory(inputStream, destination) {
        def unzippedFiles = []
        new ZipInputStream(inputStream).withStream { zipInput ->
            def entry
            while (entry = zipInput.nextEntry) {
                if (!entry.isDirectory()) {
                    def file = new File(destination, entry.name)
                    file.parentFile?.mkdirs()
                    new FileOutputStream(file).withStream {
                        it << zipInput
                    }
                    unzippedFiles << file
                }
            }
        }
        unzippedFiles
    }

    private void writeScenarioReport() {
        def renderer = new ScenarioReportRenderer()
        IoActions.writeTextFile(scenarioReport) { Writer writer ->
            renderer.render(writer, project.name, finishedBuilds, testResultFilesForBuild)
        }
        renderer.writeCss(scenarioReport.getParentFile())
    }

    @TypeChecked(TypeCheckingMode.SKIP)
    private void checkForErrors() {
        def failedBuilds = finishedBuilds.findAll { it.@status != "SUCCESS"}
        if (failedBuilds) {
            throw new GradleException("${failedBuilds.size()} performance tests failed. See $scenarioReport for details.")
        }
    }

    private RESTClient createClient() {
        client = new RESTClient("$teamCityUrl/httpAuth/app/rest/9.1")
        client.auth.basic(teamCityUsername, teamCityPassword)
        client
    }


    private static class Scenario {
        String id
        long estimatedRuntime
        List<String> templates
    }

}
