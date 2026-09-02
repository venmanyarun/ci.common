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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DevUtilContainerNameTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void testSanitize_null() {
        assertEquals("", DevUtil.sanitizeContainerNameSegment(null));
    }

    @Test
    public void testSanitize_emptyString() {
        assertEquals("", DevUtil.sanitizeContainerNameSegment(""));
    }

    @Test
    public void testSanitize_blankString() {
        assertEquals("", DevUtil.sanitizeContainerNameSegment("   "));
    }

    @Test
    public void testSanitize_simpleAlphanumeric() {
        assertEquals("myapp", DevUtil.sanitizeContainerNameSegment("myApp"));
    }

    @Test
    public void testSanitize_hyphenAndDot() {
        assertEquals("my-app.v1", DevUtil.sanitizeContainerNameSegment("my-app.v1"));
    }

    @Test
    public void testSanitize_specialCharactersReplacedWithHyphen() {
        assertEquals("com.example-myapp", DevUtil.sanitizeContainerNameSegment("com.example:myApp"));
        assertEquals("my-app", DevUtil.sanitizeContainerNameSegment("my app"));
        assertEquals("a-b", DevUtil.sanitizeContainerNameSegment("a/b"));
    }

    @Test
    public void testSanitize_consecutiveSeparatorsCollapsed() {
        assertEquals("a-b", DevUtil.sanitizeContainerNameSegment("a..b"));
        assertEquals("a-b", DevUtil.sanitizeContainerNameSegment("a--b"));
        assertEquals("a-b", DevUtil.sanitizeContainerNameSegment("a_.b"));
    }

    @Test
    public void testSanitize_leadingAndTrailingSeparatorsRemoved() {
        assertEquals("app", DevUtil.sanitizeContainerNameSegment("-app-"));
        assertEquals("app", DevUtil.sanitizeContainerNameSegment("..app.."));
    }

    @Test
    public void testSanitize_truncatesLongInput() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 70; i++) sb.append('a');
        String result = DevUtil.sanitizeContainerNameSegment(sb.toString());
        assertTrue("Sanitized segment must be <= 63 chars", result.length() <= 63);
    }

    @Test
    public void testSanitize_upperCaseLowered() {
        assertEquals("moduleabc", DevUtil.sanitizeContainerNameSegment("ModuleABC"));
    }

    private class ContainerNameTestUtil extends DevUtil {

        private final String fakeContainerList;

        ContainerNameTestUtil(String applicationId, String fakeContainerList) throws IOException {
            super(temp.newFolder(),
                  null,
                  null, null, null,
                  null, null,
                  null,
                  false, false, false, false, false, false,
                  applicationId,
                  30, 30, 5, 500,
                  true, false, false, false,
                  false,
                  null, null, null, 0, false, null, false,
                  null, null, false, null, null, null, false,
                  null, null, null, Collections.emptyMap());
            this.fakeContainerList = fakeContainerList;
        }

        public String callGenerateNewContainerName() throws PluginExecutionException {
            return generateNewContainerName();
        }

        @Override
        protected String execContainerCmdWithPrefix(String command, int timeout, boolean throwExceptionOnError) {
            return fakeContainerList;
        }

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

    @Test
    public void testGenerateContainerName_noExistingContainers() throws Exception {
        ContainerNameTestUtil util = new ContainerNameTestUtil("moduleA", null);
        String name = util.callGenerateNewContainerName();
        assertEquals("liberty-dev-modulea", name);
    }

    @Test
    public void testGenerateContainerName_preferredNameFree() throws Exception {
        ContainerNameTestUtil util = new ContainerNameTestUtil("moduleA", "some-other-container liberty-dev-moduleb");
        String name = util.callGenerateNewContainerName();
        assertEquals("liberty-dev-modulea", name);
    }

    @Test
    public void testGenerateContainerName_preferredNameTaken_firstSuffix() throws Exception {
        ContainerNameTestUtil util = new ContainerNameTestUtil("moduleA", "liberty-dev-modulea");
        String name = util.callGenerateNewContainerName();
        assertEquals("liberty-dev-modulea-1", name);
    }

    @Test
    public void testGenerateContainerName_preferredNameAndFirstSuffixTaken() throws Exception {
        ContainerNameTestUtil util = new ContainerNameTestUtil("moduleA", "liberty-dev-modulea liberty-dev-modulea-1");
        String name = util.callGenerateNewContainerName();
        assertEquals("liberty-dev-modulea-2", name);
    }

    @Test
    public void testGenerateContainerName_differentModulesGetDifferentNames() throws Exception {
        ContainerNameTestUtil utilA = new ContainerNameTestUtil("moduleA", null);
        ContainerNameTestUtil utilB = new ContainerNameTestUtil("moduleB", null);
        assertNotEquals(utilA.callGenerateNewContainerName(), utilB.callGenerateNewContainerName());
    }

    @Test
    public void testGenerateContainerName_nullApplicationId() throws Exception {
        ContainerNameTestUtil util = new ContainerNameTestUtil(null, null);
        String name = util.callGenerateNewContainerName();
        assertEquals("liberty-dev", name);
    }

    @Test
    public void testGenerateContainerName_applicationIdWithSpecialChars() throws Exception {
        ContainerNameTestUtil util = new ContainerNameTestUtil("com.example:my-App", null);
        String name = util.callGenerateNewContainerName();
        assertEquals("liberty-dev-com.example-my-app", name);
    }
}
