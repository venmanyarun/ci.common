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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link DevUtil#resolveEffectiveContainerPort} (backed by
 * {@link LibertyPortResolutionUtil}).
 *
 * <p>Test layout mirrors a typical Maven/Gradle build output:
 * <pre>
 *   buildDir/
 *     liberty-plugin-config.xml
 *   serverDir/
 *     server.xml
 *     server.env                    (optional)
 *     bootstrap.properties          (optional)
 *     configDropins/
 *       defaults/
 *         liberty-plugin-variable-config.xml  (optional)
 *       overrides/
 *         liberty-plugin-variable-config.xml  (optional)
 * </pre>
 */
public class DevUtilResolvePortTest extends BaseDevUtilTest {

    private static final int DEFAULT_HTTP  = 9080;
    private static final int DEFAULT_HTTPS = 9443;

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private File buildDir;
    private File serverDir;
    private DevTestUtil util;

    @Before
    public void setUp() throws IOException {
        buildDir  = root.newFolder("build");
        serverDir = root.newFolder("server");
        util = new DevTestUtil(serverDir, buildDir);
    }

    // ---- helpers ------------------------------------------------------------

    private void writePluginConfig(File configFile,
                                   File bootstrapPropertiesFile,
                                   File serverEnvFile) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<liberty-plugin-config>\n");
        sb.append("  <installDirectory>").append(serverDir.getAbsolutePath()).append("</installDirectory>\n");
        sb.append("  <userDirectory>").append(serverDir.getAbsolutePath()).append("</userDirectory>\n");
        sb.append("  <serverDirectory>").append(serverDir.getAbsolutePath()).append("</serverDirectory>\n");
        if (configFile != null) {
            sb.append("  <configFile>").append(configFile.getAbsolutePath()).append("</configFile>\n");
        }
        if (bootstrapPropertiesFile != null) {
            sb.append("  <bootstrapPropertiesFile>")
              .append(bootstrapPropertiesFile.getAbsolutePath())
              .append("</bootstrapPropertiesFile>\n");
        }
        if (serverEnvFile != null) {
            sb.append("  <serverEnv>").append(serverEnvFile.getAbsolutePath()).append("</serverEnv>\n");
        }
        sb.append("</liberty-plugin-config>\n");
        try (FileWriter fw = new FileWriter(new File(buildDir, LibertyPortResolutionUtil.PLUGIN_CONFIG_XML))) {
            fw.write(sb.toString());
        }
    }

    private void writePluginConfig() throws IOException {
        writePluginConfig(serverXmlFile(), null, null);
    }

    private File serverXmlFile() {
        return new File(serverDir, "server.xml");
    }

    private void writeServerXml(String content) throws IOException {
        try (FileWriter fw = new FileWriter(serverXmlFile())) {
            fw.write(content);
        }
    }

    private void writeConfigDropinsFile(String subDir, String filename, String content) throws IOException {
        File dir = new File(serverDir, "configDropins/" + subDir);
        dir.mkdirs();
        try (FileWriter fw = new FileWriter(new File(dir, filename))) {
            fw.write(content);
        }
    }

    private void writeVariableConfigOverrides(String content) throws IOException {
        writeConfigDropinsFile("overrides", "liberty-plugin-variable-config.xml", content);
    }

    private void writeVariableConfigDefaults(String content) throws IOException {
        writeConfigDropinsFile("defaults", "liberty-plugin-variable-config.xml", content);
    }

    private File writeBootstrapProperties(String content) throws IOException {
        File f = new File(serverDir, "bootstrap.properties");
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(content);
        }
        return f;
    }

    private File writeServerEnv(String content) throws IOException {
        File f = new File(serverDir, "server.env");
        try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
            pw.print(content);
        }
        return f;
    }

    // ---- plugin-config absent or degenerate ---------------------------------

    @Test
    public void testNullBuildDirectory_returnsDefault() {
        DevTestUtil utilNoDir = new DevTestUtil(null, null, false);
        assertEquals(DEFAULT_HTTP,  utilNoDir.resolveEffectiveContainerPort(DEFAULT_HTTP,  "httpPort"));
        assertEquals(DEFAULT_HTTPS, utilNoDir.resolveEffectiveContainerPort(DEFAULT_HTTPS, "httpsPort"));
    }

    @Test
    public void testNoPluginConfig_returnsDefault() {
        assertEquals(DEFAULT_HTTP,  util.resolveEffectiveContainerPort(DEFAULT_HTTP,  "httpPort"));
        assertEquals(DEFAULT_HTTPS, util.resolveEffectiveContainerPort(DEFAULT_HTTPS, "httpsPort"));
    }

    @Test
    public void testPluginConfigPointsToMissingServerXml_returnsDefault() throws Exception {
        writePluginConfig(serverXmlFile(), null, null);
        assertEquals(DEFAULT_HTTP, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    @Test
    public void testPluginConfigPointsToEmptyServerXml_returnsDefault() throws Exception {
        Files.write(serverXmlFile().toPath(), new byte[0]);
        writePluginConfig();
        assertEquals(DEFAULT_HTTP, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    @Test
    public void testServerXmlWithNoHttpEndpoint_returnsDefault() throws Exception {
        writeServerXml("<server><featureManager><feature>servlet-6.0</feature></featureManager></server>");
        writePluginConfig();
        assertEquals(DEFAULT_HTTP,  util.resolveEffectiveContainerPort(DEFAULT_HTTP,  "httpPort"));
        assertEquals(DEFAULT_HTTPS, util.resolveEffectiveContainerPort(DEFAULT_HTTPS, "httpsPort"));
    }

    // ---- literal port values in server.xml ----------------------------------

    @Test
    public void testLiteralNonDefaultPorts_returnConfiguredValues() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint httpPort=\"9090\" httpsPort=\"9453\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writePluginConfig();
        assertEquals(9090, util.resolveEffectiveContainerPort(DEFAULT_HTTP,  "httpPort"));
        assertEquals(9453, util.resolveEffectiveContainerPort(DEFAULT_HTTPS, "httpsPort"));
    }

    @Test
    public void testLiteralDefaultPorts_returnDefaultValues() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint httpPort=\"9080\" httpsPort=\"9443\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writePluginConfig();
        assertEquals(DEFAULT_HTTP,  util.resolveEffectiveContainerPort(DEFAULT_HTTP,  "httpPort"));
        assertEquals(DEFAULT_HTTPS, util.resolveEffectiveContainerPort(DEFAULT_HTTPS, "httpsPort"));
    }

    @Test
    public void testHttpEndpointPresentButAttributeEmpty_returnsDefault() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writePluginConfig();
        assertEquals(DEFAULT_HTTP,  util.resolveEffectiveContainerPort(DEFAULT_HTTP,  "httpPort"));
        assertEquals(DEFAULT_HTTPS, util.resolveEffectiveContainerPort(DEFAULT_HTTPS, "httpsPort"));
    }

    @Test
    public void testMalformedPortAttribute_notVariableNotInteger_returnsDefault() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint httpPort=\"not-a-var-or-int\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writePluginConfig();
        assertEquals(DEFAULT_HTTP, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    // ---- variable resolution: configDropins ---------------------------------

    @Test
    public void testVariableFromConfigDropinsOverrides_resolvedCorrectly() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint httpPort=\"${default.http.port}\" httpsPort=\"${default.https.port}\""
                + " host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writeVariableConfigOverrides(
                "<server>"
                + "<variable name=\"default.http.port\"  value=\"9090\"/>"
                + "<variable name=\"default.https.port\" value=\"9453\"/>"
                + "</server>");
        writePluginConfig();
        assertEquals(9090, util.resolveEffectiveContainerPort(DEFAULT_HTTP,  "httpPort"));
        assertEquals(9453, util.resolveEffectiveContainerPort(DEFAULT_HTTPS, "httpsPort"));
    }

    @Test
    public void testVariableFromConfigDropinsDefaults_resolvedCorrectly() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint httpPort=\"${default.http.port}\" httpsPort=\"${default.https.port}\""
                + " host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writeVariableConfigDefaults(
                "<server>"
                + "<variable name=\"default.http.port\"  defaultValue=\"9091\"/>"
                + "<variable name=\"default.https.port\" defaultValue=\"9454\"/>"
                + "</server>");
        writePluginConfig();
        assertEquals(9091, util.resolveEffectiveContainerPort(DEFAULT_HTTP,  "httpPort"));
        assertEquals(9454, util.resolveEffectiveContainerPort(DEFAULT_HTTPS, "httpsPort"));
    }

    @Test
    public void testConfigDropinsOverridesBeatsDefaults() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint httpPort=\"${my.port}\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writeVariableConfigOverrides("<server><variable name=\"my.port\" value=\"9099\"/></server>");
        writeVariableConfigDefaults("<server><variable name=\"my.port\" defaultValue=\"8888\"/></server>");
        writePluginConfig();
        assertEquals(9099, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    @Test
    public void testConfigDropinsDefaultsValueBeatsServerXmlDefaultValue() throws Exception {
        writeServerXml("<server>"
                + "<variable name=\"my.port\" defaultValue=\"7777\"/>"
                + "<httpEndpoint httpPort=\"${my.port}\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writeConfigDropinsFile("defaults", "extra-vars.xml",
                "<server><variable name=\"my.port\" value=\"8181\"/></server>");
        writePluginConfig();
        assertEquals(8181, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    @Test
    public void testConfigDropinsOverridesValueBeatsServerXmlValue() throws Exception {
        writeServerXml("<server>"
                + "<variable name=\"my.port\" value=\"8080\"/>"
                + "<httpEndpoint httpPort=\"${my.port}\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writeConfigDropinsFile("overrides", "extra-vars.xml",
                "<server><variable name=\"my.port\" value=\"9696\"/></server>");
        writePluginConfig();
        assertEquals(9696, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    // ---- variable resolution: server.xml defaultValue vs value --------------

    @Test
    public void testServerXmlDefaultValue_usedWhenNothingElseDefinesVariable() throws Exception {
        writeServerXml("<server>"
                + "<variable name=\"my.port\" defaultValue=\"7777\"/>"
                + "<httpEndpoint httpPort=\"${my.port}\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writePluginConfig();
        assertEquals(7777, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    @Test
    public void testServerXmlValueBeatsServerXmlDefaultValue() throws Exception {
        writeServerXml("<server>"
                + "<variable name=\"my.port\" defaultValue=\"7777\"/>"
                + "<variable name=\"my.port\" value=\"8888\"/>"
                + "<httpEndpoint httpPort=\"${my.port}\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writePluginConfig();
        assertEquals(8888, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    // ---- variable resolution: bootstrap.properties --------------------------

    @Test
    public void testVariableFromBootstrapProperties_resolvedCorrectly() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint httpPort=\"${default.http.port}\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        File bootstrap = writeBootstrapProperties("default.http.port=9092\n");
        writePluginConfig(serverXmlFile(), bootstrap, null);
        assertEquals(9092, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    @Test
    public void testBootstrapInclude_variableFromIncludedFile() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint httpPort=\"${my.port}\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        File included = new File(serverDir, "extra.properties");
        try (FileWriter fw = new FileWriter(included)) {
            fw.write("my.port=9191\n");
        }
        File bootstrap = writeBootstrapProperties("bootstrap.include=extra.properties\n");
        writePluginConfig(serverXmlFile(), bootstrap, null);
        assertEquals(9191, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    @Test
    public void testBootstrapPropertiesBeatsServerEnv() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint httpPort=\"${my.port}\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        File serverEnv = writeServerEnv("my.port=8181\n");
        File bootstrap = writeBootstrapProperties("my.port=7070\n");
        writePluginConfig(serverXmlFile(), bootstrap, serverEnv);
        assertEquals(7070, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    // ---- variable resolution: server.env ------------------------------------

    @Test
    public void testVariableFromServerEnv_resolvedCorrectly() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint httpPort=\"${default.http.port}\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        File serverEnv = writeServerEnv("default.http.port=9093\n");
        writePluginConfig(serverXmlFile(), null, serverEnv);
        assertEquals(9093, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    @Test
    public void testServerEnvBeatsServerXmlDefaultValue() throws Exception {
        writeServerXml("<server>"
                + "<variable name=\"my.port\" defaultValue=\"7777\"/>"
                + "<httpEndpoint httpPort=\"${my.port}\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        File serverEnv = writeServerEnv("my.port=8282\n");
        writePluginConfig(serverXmlFile(), null, serverEnv);
        assertEquals(8282, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    // ---- unresolvable / bad values ------------------------------------------

    @Test
    public void testVariableNotDefined_returnsDefault() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint httpPort=\"${unknown.port}\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writePluginConfig();
        assertEquals(DEFAULT_HTTP, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }

    @Test
    public void testVariableResolvesToNonNumericString_returnsDefault() throws Exception {
        writeServerXml("<server>"
                + "<httpEndpoint httpPort=\"${my.port}\" host=\"*\" id=\"defaultHttpEndpoint\"/>"
                + "</server>");
        writeVariableConfigOverrides("<server><variable name=\"my.port\" value=\"notANumber\"/></server>");
        writePluginConfig();
        assertEquals(DEFAULT_HTTP, util.resolveEffectiveContainerPort(DEFAULT_HTTP, "httpPort"));
    }
}
