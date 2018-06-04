/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.internal.resource.local;

import com.google.common.base.Preconditions;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks access to files by touching, i.e. updating the last modified
 * timestamp, files and directory at the supplied depth within the supplied base
 * directory.
 */
@SuppressWarnings("Since15")
public class SingleDepthFileAccessTracker implements FileAccessTracker {

    private final Path baseDir;
    private final int endNameIndex;
    private final int startNameIndex;
    private final FileAccessTimeWriter writer;

    public SingleDepthFileAccessTracker(FileAccessTimeWriter writer, File baseDir, int depth) {
        this.writer = writer;
        Preconditions.checkArgument(depth > 0, "depth must be > 0: %s", depth);
        this.baseDir = baseDir.toPath().toAbsolutePath();
        this.startNameIndex = this.baseDir.getNameCount();
        this.endNameIndex = startNameIndex + depth;
    }

    public void markAccessed(Collection<File> files) {
        for (Path path : collectSubPaths(files)) {
            writer.setLastAccessTime(path.toFile(), System.currentTimeMillis());
        }
    }

    private Set<Path> collectSubPaths(Collection<File> files) {
        Set<Path> paths = new HashSet<Path>();
        for (File file : files) {
            Path path = file.toPath().toAbsolutePath();
            if (path.getNameCount() >= endNameIndex && path.startsWith(baseDir)) {
                paths.add(baseDir.resolve(path.subpath(startNameIndex, endNameIndex)));
            }
        }
        return paths;
    }
}
