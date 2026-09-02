/**
 * (C) Copyright IBM Corporation 2026.
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
package io.openliberty.tools.common.plugins.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DevUtilPortHoldingTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private class PortTestUtil extends DevUtil {

        PortTestUtil() throws IOException {
            super(temp.newFolder(),
                  null, null, null, null, null, null,
                  null, false, false, false, false, false, false,
                  "test-app", 30, 30, 5, 500,
                  true, false, false, false,
                  false, null, null, null, 0, false, null, false,
                  null, null, false, null, null, null, false,
                  null, null, null, Collections.emptyMap());
        }

        ServerSocket callBindPortSocket(int port) throws Exception {
            Method m = DevUtil.class.getDeclaredMethod("bindPortSocket", int.class);
            m.setAccessible(true);
            return (ServerSocket) m.invoke(this, port);
        }

        @Override protected String execContainerCmdWithPrefix(String cmd, int t, boolean e) { return null; }
        @Override public void debug(String msg) {}
        @Override public void debug(String msg, Throwable e) {}
        @Override public void debug(Throwable e) {}
        @Override public void warn(String msg) {}
        @Override public void info(String msg) {}
        @Override public void error(String msg) {}
        @Override public void error(String msg, Throwable e) {}
        @Override public boolean isDebugEnabled() { return false; }
        @Override public void stopServer() {}
        @Override public io.openliberty.tools.ant.ServerTask getServerTask() { return null; }
        @Override public boolean recompileBuildFile(File f, Set<String> c, Set<String> t, boolean g, ThreadPoolExecutor e) { return false; }
        @Override public boolean updateArtifactPaths(ProjectModule m, boolean r, boolean g, ThreadPoolExecutor e) { return false; }
        @Override public boolean updateArtifactPaths(File f) { return false; }
        @Override public int countApplicationUpdatedMessages() { return 0; }
        @Override public void runTests(boolean w, int m, ThreadPoolExecutor e, boolean a, boolean b, boolean c, File f, String s) {}
        @Override public void installFeatures(File f, File s, boolean g) {}
        @Override public ServerFeatureUtil getServerFeatureUtilObj() { return null; }
        @Override public Set<String> getExistingFeatures() { return null; }
        @Override public void updateExistingFeatures() {}
        @Override public boolean compile(File d) { return false; }
        @Override public void runUnitTests(File f) {}
        @Override public void runIntegrationTests(File f) {}
        @Override public void libertyCreate() {}
        @Override public void libertyDeploy() {}
        @Override public void libertyInstallFeature() {}
        @Override public boolean libertyGenerateFeatures(Collection<String> c, boolean o) { return false; }
        @Override public void redeployApp() {}
        @Override public String getServerStartTimeoutExample() { return null; }
        @Override public String getProjectName() { return null; }
        @Override public boolean isLooseApplication() { return true; }
        @Override public File getLooseApplicationFile() { return null; }
        @Override public boolean compile(File d, ProjectModule p) { return false; }
        @Override protected void updateLooseApp() {}
        @Override protected void resourceDirectoryCreated() {}
        @Override protected void resourceModifiedOrCreated(File f, File r, File o) {}
        @Override protected void resourceDeleted(File f, File r, File o) {}
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    @Test
    public void testFindAvailablePort_returnsFreePort() throws Exception {
        PortTestUtil util = new PortTestUtil();
        int freePort = findFreePort();
        int found = util.findAvailablePort(freePort, false);
        assertTrue("findAvailablePort must return a port >= 1024", found >= 1024);
    }

    @Test
    public void testBindPortSocket_succeeds_onFreePort() throws Exception {
        PortTestUtil util = new PortTestUtil();
        int port = findFreePort();
        ServerSocket sock = util.callBindPortSocket(port);
        try {
            assertNotNull("bindPortSocket should return a non-null socket for a free port", sock);
            assertEquals("Bound port must match requested port", port, sock.getLocalPort());
        } finally {
            if (sock != null) sock.close();
        }
    }

    @Test
    public void testBindPortSocket_returnsNull_whenPortAlreadyBound() throws Exception {
        PortTestUtil util = new PortTestUtil();
        int port = findFreePort();
        try (ServerSocket occupier = new ServerSocket()) {
            occupier.setReuseAddress(false);
            occupier.bind(new InetSocketAddress(InetAddress.getByName(null), port), 1);

            ServerSocket result = util.callBindPortSocket(port);
            assertNull("bindPortSocket must return null when the port is already in use", result);
        }
    }

    @Test
    public void testPortHeldByBindSocket_notReturnedByFindAvailablePort() throws Exception {
        PortTestUtil util = new PortTestUtil();
        int port = findFreePort();
        ServerSocket held = util.callBindPortSocket(port);
        assertNotNull("Pre-condition: bindPortSocket should succeed on a free port", held);
        try {
            int next = util.findAvailablePort(port, false);
            assertNotEquals(
                "findAvailablePort must not return a port that is already held open",
                port, next);
        } finally {
            held.close();
        }
    }

    @Test
    public void testTwoSequentialFindAvailablePort_returnDifferentPorts_whenFirstIsHeld() throws Exception {
        PortTestUtil util = new PortTestUtil();
        int preferredPort = findFreePort();

        int portA = util.findAvailablePort(preferredPort, false);
        ServerSocket holdA = util.callBindPortSocket(portA);
        assertNotNull("Module A must be able to hold its chosen port", holdA);

        try {
            int portB = util.findAvailablePort(preferredPort, false);
            assertNotEquals(
                "Module B must receive a different port from module A's held port",
                portA, portB);
        } finally {
            holdA.close();
        }
    }
}
