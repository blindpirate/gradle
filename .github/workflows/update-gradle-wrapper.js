const fs = require('fs');
const https = require('https');

async function fetchGradleVersions() {
    return new Promise((resolve, reject) => {
        https.get('https://services.gradle.org/versions/all', (res) => {
            let data = '';
            res.on('data', (chunk) => data += chunk);
            res.on('end', () => resolve(JSON.parse(data)));
        }).on('error', reject);
    });
}

async function main() {
    try {
        const version = fs.readFileSync('version.txt', 'utf8').trim();
        
        const today = new Date().toISOString().split('T')[0].replace(/-/g, '');
        
        const versions = await fetchGradleVersions();
        
        const todaysBuild = versions.find(v => 
            v.version.startsWith(`${version}-`) && 
            v.buildTime.startsWith(today)
        );
        
        if (!todaysBuild) {
            console.log('No matching nightly build found for today');
            return;
        }
        
        const branchName = `upgradle-to-latest-wrapper-${new Date().toISOString().split('T')[0]}`;
        
        const wrapperPath = 'gradle/wrapper/gradle-wrapper.properties';
        const properties = fs.readFileSync(wrapperPath, 'utf8');
        
        const updatedProperties = properties.replace(
            /distributionUrl=.+/,
            `distributionUrl=${todaysBuild.downloadUrl}`
        );
        
        fs.writeFileSync(wrapperPath, updatedProperties);
        
        const { execSync } = require('child_process');
        const commands = [
            `git config --global user.name "GitHub Action"`,
            `git config --global user.email "action@github.com"`,
            `git checkout -b ${branchName}`,
            `git add ${wrapperPath}`,
            `git commit -m "Update Gradle wrapper to ${todaysBuild.version}"`,
            `git push origin ${branchName}`
        ];
        
        commands.forEach(cmd => {
            execSync(cmd, { stdio: 'inherit' });
        });
        
        console.log(`Successfully updated Gradle wrapper to version ${todaysBuild.version}`);
        
    } catch (error) {
        console.error('Error:', error);
        process.exit(1);
    }
}

main(); 