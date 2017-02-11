/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent;

import java.io.File;
import java.net.URISyntaxException;
import java.security.CodeSource;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.h2.tools.Console;
import org.h2.tools.Recover;
import org.h2.tools.RunScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OfflineViewer {

    // need to wait to init logger until
    private static volatile @MonotonicNonNull Logger startupLogger;

    private OfflineViewer() {}

    public static void main(String... args) throws Exception {
        CodeSource codeSource = OfflineViewer.class.getProtectionDomain().getCodeSource();
        File glowrootJarFile = getGlowrootJarFile(codeSource);
        File glowrootDir = GlowrootDir.getGlowrootDir(glowrootJarFile);
        File agentDir = GlowrootDir.getAgentDir(glowrootDir);
        MainEntryPoint.initLogging(glowrootDir);
        startupLogger = LoggerFactory.getLogger("org.glowroot");

        if (args.length == 1 && args[0].equals("h2")) {
            h2(agentDir);
            return;
        }
        if (args.length == 1 && args[0].equals("recover")) {
            recover(agentDir);
            return;
        }
        MainEntryPoint.runViewer(glowrootDir, agentDir);
    }

    @VisibleForTesting
    static @Nullable File getGlowrootJarFile(@Nullable CodeSource codeSource)
            throws URISyntaxException {
        if (codeSource == null) {
            return null;
        }
        File codeSourceFile = new File(codeSource.getLocation().toURI());
        if (codeSourceFile.getName().endsWith(".jar")) {
            return codeSourceFile;
        }
        return null;
    }

    private static void h2(File agentDir) throws Exception {
        File dataDir = new File(agentDir, "data");
        Console.main(new String[] {"-url", "jdbc:h2:" + dataDir.getPath() + File.separator + "data",
                "-user", "sa"});
    }

    @RequiresNonNull("startupLogger")
    private static void recover(File agentDir) throws Exception {
        File dataDir = new File(agentDir, "data");
        File recoverFile = new File(dataDir, "data.h2.sql");
        if (recoverFile.exists() && !recoverFile.delete()) {
            startupLogger.warn("recover failed: cannot delete existing data.h2.sql");
        }
        Recover.main(new String[] {"-dir", dataDir.getPath(), "-db", "data"});
        File dbFile = new File(dataDir, "data.h2.db");
        File dbBakFile = new File(dataDir, "data.h2.db.bak");
        if (dbBakFile.exists() && !dbBakFile.delete()) {
            startupLogger.warn("recover failed, cannot delete existing file: {}",
                    dbBakFile.getPath());
        }
        if (!dbFile.renameTo(dbBakFile)) {
            startupLogger.warn("recover failed, cannot rename {} to {}", dbFile.getPath(),
                    dbBakFile.getPath());
            return;
        }
        RunScript.main(
                new String[] {"-url", "jdbc:h2:" + dataDir.getPath() + File.separator + "data",
                        "-script", recoverFile.getPath()});
        startupLogger.info("recover succeeded");

        // clean up
        if (!dbBakFile.delete()) {
            startupLogger.info("failed to clean-up, cannot delete file: {}", dbBakFile.getPath());
        }
        if (!recoverFile.delete()) {
            startupLogger.info("failed to clean-up, cannot delete file: {}", recoverFile.getPath());
        }
    }
}
