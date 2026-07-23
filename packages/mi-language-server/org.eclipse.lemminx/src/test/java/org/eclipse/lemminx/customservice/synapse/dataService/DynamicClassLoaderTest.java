/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     WSO2 LLC - support for WSO2 Micro Integrator Configuration
 */

package org.eclipse.lemminx.customservice.synapse.dataService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DynamicClassLoaderTest {

    @Test
    void twoProjectsGetIndependentLoaders(@TempDir Path tempDir) throws Exception {
        String projectA = tempDir.resolve("projectA").toString();
        String projectB = tempDir.resolve("projectB").toString();

        URLClassLoader loaderA = DynamicClassLoader.getClassLoader(projectA);
        URLClassLoader loaderB = DynamicClassLoader.getClassLoader(projectB);

        assertNotEquals(loaderA, loaderB, "Two different projects must not share a classloader instance");
    }

    @Test
    void loadingProjectBDriversDoesNotEvictProjectADrivers(@TempDir Path tempDir) throws Exception {
        String projectA = tempDir.resolve("projectA_" + System.nanoTime()).toString();
        String projectB = tempDir.resolve("projectB_" + System.nanoTime()).toString();

        Path jarADir = Files.createDirectories(Path.of(projectA, "libs"));
        Path jarBDir = Files.createDirectories(Path.of(projectB, "libs"));
        File jarA = createEmptyJar(jarADir.resolve("driver-a.jar"));
        File jarB = createEmptyJar(jarBDir.resolve("driver-b.jar"));

        DynamicClassLoader.updateClassLoader(projectA, jarADir.toFile());
        DynamicClassLoader.updateClassLoader(projectB, jarBDir.toFile());

        URL[] urlsA = DynamicClassLoader.getClassLoader(projectA).getURLs();
        URL[] urlsB = DynamicClassLoader.getClassLoader(projectB).getURLs();

        assertTrue(containsUrlFor(urlsA, jarA), "Project A's loader must still contain its own driver jar");
        assertTrue(containsUrlFor(urlsB, jarB), "Project B's loader must contain its own driver jar");
        assertTrue(!containsUrlFor(urlsA, jarB), "Project A's loader must not have been polluted by project B's jar");
        assertTrue(!containsUrlFor(urlsB, jarA), "Project B's loader must not have been polluted by project A's jar");
    }

    @Test
    void uriFormAndPathFormOfSameProjectNormalizeEqual(@TempDir Path tempDir) throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("project"));
        String pathForm = projectRoot.toAbsolutePath().toString();
        String uriForm = projectRoot.toUri().toString();

        assertEquals(DynamicClassLoader.normalize(pathForm), DynamicClassLoader.normalize(uriForm),
                "URI form and absolute-path form of the same project root must key to the same registry entry");
    }

    @Test
    void removeProjectDropsTheRegistryEntry(@TempDir Path tempDir) throws Exception {
        String project = tempDir.resolve("removable").toString();

        URLClassLoader before = DynamicClassLoader.getClassLoader(project);
        DynamicClassLoader.removeProject(project);
        URLClassLoader after = DynamicClassLoader.getClassLoader(project);

        assertNotEquals(before, after, "A fresh loader must be created after the project entry is removed");
    }

    private File createEmptyJar(Path path) throws IOException {
        Files.write(path, new byte[]{0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        return path.toFile();
    }

    private boolean containsUrlFor(URL[] urls, File jar) throws Exception {
        URL target = jar.toURI().toURL();
        for (URL url : urls) {
            if (url.equals(target)) {
                return true;
            }
        }
        return false;
    }
}
