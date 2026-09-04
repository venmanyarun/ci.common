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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import io.openliberty.tools.common.CommonLoggerI;

/**
 * Focused, side-effect-free utility that resolves the effective Liberty HTTP or
 * HTTPS port for the {@code libertyDevc} container run command.
 *
 * <p>Variable precedence follows the order documented in
 * {@link io.openliberty.tools.common.plugins.config.ServerConfigDocument#initializeAppsLocation()}:
 * <ol>
 *   <li>{@code <variable defaultValue="...">} in {@code server.xml} (lowest precedence)</li>
 *   <li>{@code server.env} — read from
 *       {@code <installDir>/etc/}, {@code <userDir>/shared/}, {@code <serverConfigDir>/}</li>
 *   <li>{@code bootstrap.properties} (+ {@code bootstrap.include})</li>
 *   <li>{@code <variable value="...">} in
 *       {@code configDropins/defaults/}, main {@code server.xml},
 *       {@code configDropins/overrides/}</li>
 *   <li>{@code liberty-plugin-variable-config.xml} from
 *       {@code configDropins/overrides/} (highest; overrides file takes priority over
 *       defaults file)</li>
 * </ol>
 *
 * <p>{@code liberty.env.*} variables set by the Maven/Gradle plugin are written to
 * {@code server.env} inside {@code serverDirectory} before the container starts, so
 * reading {@code server.env} already captures them.
 *
 * <p>This class does NOT instantiate {@link io.openliberty.tools.common.plugins.config.ServerConfigDocument}
 * and has no shared mutable state.
 */
public final class LibertyPortResolutionUtil {

    /** Filename written by the Maven/Gradle plugin into the build output directory. */
    public static final String PLUGIN_CONFIG_XML = "liberty-plugin-config.xml";

    /** Sentinel empty map passed to {@link VariableUtility#resolveVariables} — always safe to share. */
    private static final Map<String, File> EMPTY_DIR_MAP = Collections.emptyMap();

    private LibertyPortResolutionUtil() { /* utility class */ }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Resolves the effective value of the named {@code httpEndpoint} attribute
     * by reading all paths from a {@code liberty-plugin-config.xml} file generated
     * by the Maven or Gradle Liberty plugin.
     *
     * <p>The following elements are read from the config file:
     * <ul>
     *   <li>{@code <installDirectory>} — Liberty install dir (for {@code etc/server.env})</li>
     *   <li>{@code <userDirectory>} — Liberty user dir (for {@code shared/server.env})</li>
     *   <li>{@code <serverDirectory>} — deployed server dir (for {@code configDropins/})</li>
     *   <li>{@code <configFile>} — the effective {@code server.xml} path</li>
     *   <li>{@code <bootstrapPropertiesFile>} — explicit bootstrap.properties path (optional)</li>
     *   <li>{@code <serverEnv>} — explicit server.env path (optional)</li>
     * </ul>
     *
     * @param pluginConfigXml  the {@code liberty-plugin-config.xml} file
     *                         (typically {@code <buildDirectory>/liberty-plugin-config.xml})
     * @param endpointAttr     the attribute to resolve: {@code "httpPort"} or {@code "httpsPort"}
     * @param defaultPort      value to return when resolution fails
     * @return the resolved port, or {@code defaultPort} on any failure
     */
    public static int resolvePort(File pluginConfigXml, String endpointAttr, int defaultPort) {
        if (pluginConfigXml == null || !pluginConfigXml.isFile()) {
            return defaultPort;
        }
        try {
            Document cfgDoc = parseDocument(pluginConfigXml);
            if (cfgDoc == null) {
                return defaultPort;
            }
            PluginConfig cfg = PluginConfig.from(cfgDoc);
            return resolvePort(cfg.serverXmlFile, cfg.serverDirectory,
                    cfg.installDirectory, cfg.userDirectory,
                    cfg.bootstrapPropertiesFile, cfg.serverEnvFile,
                    endpointAttr, defaultPort);
        } catch (Exception e) {
            return defaultPort;
        }
    }

    /**
     * Resolves the effective value of the named {@code httpEndpoint} attribute
     * ({@code "httpPort"} or {@code "httpsPort"}) in the Liberty server configuration.
     *
     * <p>All file arguments except {@code serverXmlFile} may be {@code null}; absent
     * or non-existent files are silently skipped.
     *
     * @param serverXmlFile           the effective {@code server.xml}
     * @param serverDirectory         deployed server dir — for {@code configDropins/}
     * @param installDirectory        Liberty install dir — for {@code etc/server.env}
     * @param userDirectory           Liberty user dir — for {@code shared/server.env}
     * @param endpointAttr            {@code "httpPort"} or {@code "httpsPort"}
     * @param defaultPort             value to return when resolution fails
     * @return the resolved port, or {@code defaultPort} on any failure
     */
    public static int resolvePort(File serverXmlFile, File serverDirectory,
            File installDirectory, File userDirectory,
            String endpointAttr, int defaultPort) {
        return resolvePort(serverXmlFile, serverDirectory,
                installDirectory, userDirectory,
                null, null,
                endpointAttr, defaultPort);
    }
    
     /**
     * Full resolution with custom override paths for bootstrap.properties and server.env.
     * When {@code customBootstrapFile} or {@code customServerEnvFile} are non-null and exist,
     * they are used instead of the default {@code <serverDirectory>/bootstrap.properties} /
     * {@code <serverDirectory>/server.env} fallbacks.
     */
    private static int resolvePort(File serverXmlFile, File serverDirectory,
            File installDirectory, File userDirectory,
            File customBootstrapFile, File customServerEnvFile,
            String endpointAttr, int defaultPort) {

        if (serverXmlFile == null || !serverXmlFile.isFile()) {
            return defaultPort;
        }

        try {
            Document serverDoc = parseDocument(serverXmlFile);
            if (serverDoc == null) {
                return defaultPort;
            }

            // --- Build variable maps following Liberty precedence ---

            Properties defaultProps = new Properties(); // lowest precedence
            Properties props        = new Properties(); // higher precedence values

            // 1. <variable defaultValue="..."> in server.xml
            mergeVariables(VariableUtility.parseVariables(serverDoc, true, false, false),
                    props, defaultProps);

            // 2. server.env — install/etc → user/shared → server config dir
            //    Use customServerEnvFile from plugin config when available.
            loadServerEnv(installDirectory, userDirectory, serverDirectory, customServerEnvFile, props);

            // 3. bootstrap.properties (+ bootstrap.include)
            //    Use customBootstrapFile from plugin config when available.
            loadBootstrapProperties(serverDirectory, customBootstrapFile, props);

            // 4a. <variable value/defaultValue="..."> from configDropins/defaults/
            loadConfigDropinsVariables(serverDirectory, "defaults", props, defaultProps);

            // 4b. <variable value="..."> from main server.xml
            mergeVariables(VariableUtility.parseVariables(serverDoc, false, true, false),
                    props, defaultProps);

            // 4c. <variable value/defaultValue="..."> from configDropins/overrides/
            loadConfigDropinsVariables(serverDirectory, "overrides", props, defaultProps);

            // 5. liberty-plugin-variable-config.xml:
            //    overrides file > defaults file  (both read; overrides written last so they win)
            loadPluginVariableConfig(serverDirectory, "defaults",   props, defaultProps);
            loadPluginVariableConfig(serverDirectory, "overrides",  props, defaultProps);

            // --- Resolve the httpEndpoint attribute ---
            String raw = getEndpointAttribute(serverDoc, endpointAttr);
            if (raw == null || raw.trim().isEmpty()) {
                return defaultPort;
            }
            raw = raw.trim();

            // Literal integer
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException ignored) { /* fall through to variable resolution */ }

            // Variable reference  ${varName}
            if (!isVariableReference(raw)) {
                return defaultPort; // unrecognised format
            }

            String resolved = VariableUtility.resolveVariables(
                    CommonLoggerI.noop(), raw, Collections.emptySet(), props, defaultProps, EMPTY_DIR_MAP);
            if (resolved == null) {
                return defaultPort;
            }
            try {
                return Integer.parseInt(resolved.trim());
            } catch (NumberFormatException e) {
                return defaultPort;
            }

        } catch (ParserConfigurationException | IOException | XPathExpressionException e) {
            return defaultPort;
        }
    }

    /**
     * Returns {@code true} iff {@code s} is a Liberty variable reference of the
     * form {@code ${varName}}.
     */
    private static boolean isVariableReference(String s) {
        return s.startsWith("${") && s.endsWith("}");
    }


    /**
     * Returns the value of the named attribute on the first
     * {@code <httpEndpoint>} element in the document, or {@code null} if absent.
     */
    private static String getEndpointAttribute(Document doc, String attrName)
            throws XPathExpressionException {
        Element endpoint = (Element) newXPath()
                .compile("/server/httpEndpoint")
                .evaluate(doc, XPathConstants.NODE);
        if (endpoint == null) {
            return null;
        }
        String v = endpoint.getAttribute(attrName);
        return (v == null || v.isEmpty()) ? null : v;
    }

    /**
     * Merges the result of {@link VariableUtility#parseVariables} into the caller's
     * {@code props} and {@code defaultProps} maps.  Index 0 of the list is values;
     * index 1 is default values.
     */
    private static void mergeVariables(List<Properties> parsed,
            Properties props, Properties defaultProps) {
        props.putAll(parsed.get(0));
        defaultProps.putAll(parsed.get(1));
    }

    /**
     * Reads {@code server.env} in ascending precedence order:
     * {@code <installDir>/etc/server.env}, {@code <userDir>/shared/server.env},
     * then — when {@code customServerEnvFile} is non-null and exists — that file;
     * otherwise falls back to {@code <serverDir>/server.env}.
     */
    private static void loadServerEnv(File installDirectory, File userDirectory,
            File serverDirectory, File customServerEnvFile, Properties props) {
        if (installDirectory != null) {
            loadPropertiesFile(new File(installDirectory, "etc/server.env"), props);
        }
        if (userDirectory != null) {
            loadPropertiesFile(new File(userDirectory, "shared/server.env"), props);
        }
        if (customServerEnvFile != null && customServerEnvFile.isFile()) {
            loadPropertiesFile(customServerEnvFile, props);
        } else if (serverDirectory != null) {
            loadPropertiesFile(new File(serverDirectory, "server.env"), props);
        }
    }

    /**
     * Reads bootstrap.properties and follows at most one level of
     * {@code bootstrap.include} (cycles are silently ignored).
     * When {@code customBootstrapFile} is non-null and exists it is used directly;
     * otherwise falls back to {@code <serverDir>/bootstrap.properties}.
     */
    private static void loadBootstrapProperties(File serverDirectory,
            File customBootstrapFile, Properties props) {
        File bootstrapFile = (customBootstrapFile != null && customBootstrapFile.isFile())
                ? customBootstrapFile
                : (serverDirectory != null ? new File(serverDirectory, "bootstrap.properties") : null);
        if (bootstrapFile == null || !bootstrapFile.isFile()) {
            return;
        }
        loadPropertiesFile(bootstrapFile, props);

        // Follow bootstrap.include if present (single-level, no infinite loop)
        String include = props.getProperty("bootstrap.include");
        if (include != null && !include.isEmpty()) {
            File includeFile = new File(include);
            if (!includeFile.isAbsolute()) {
                File baseDir = bootstrapFile.getParentFile();
                includeFile = (baseDir != null) ? new File(baseDir, include) : includeFile;
            }
            if (includeFile.isFile()
                    && !includeFile.getAbsolutePath().equals(bootstrapFile.getAbsolutePath())) {
                loadPropertiesFile(includeFile, props);
            }
        }
    }

    /**
     * Parses all {@code *.xml} files in {@code serverDirectory/configDropins/<subDir>/}
     * in case-insensitive alphabetical order and merges their {@code <variable>}
     * declarations into the supplied maps.
     */
    private static void loadConfigDropinsVariables(File serverDirectory, String subDir,
            Properties props, Properties defaultProps) {
        if (serverDirectory == null) {
            return;
        }
        File dir = new File(serverDirectory, "configDropins/" + subDir);
        if (!dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".xml")) {
                continue;
            }
            try {
                Document doc = parseDocument(f);
                if (doc != null) {
                    mergeVariables(VariableUtility.parseVariables(doc, false, false, true),
                            props, defaultProps);
                }
            } catch (Exception ignored) { /* skip unparseable files */ }
        }
    }

    /**
     * Reads {@code liberty-plugin-variable-config.xml} from
     * {@code serverDirectory/configDropins/<subDir>/} and merges its variables.
     * {@code value} attributes go into {@code props}; {@code defaultValue} into {@code defaultProps}.
     */
    private static void loadPluginVariableConfig(File serverDirectory, String subDir,
            Properties props, Properties defaultProps) {
        if (serverDirectory == null) {
            return;
        }
        File f = new File(serverDirectory,
                "configDropins/" + subDir + "/liberty-plugin-variable-config.xml");
        if (!f.isFile()) {
            return;
        }
        try {
            Document doc = parseDocument(f);
            if (doc != null) {
                mergeVariables(VariableUtility.parseVariables(doc, false, false, true),
                        props, defaultProps);
            }
        } catch (Exception ignored) { /* silently skip */ }
    }

    /** Loads a {@link Properties} file; silently ignores missing or unreadable files. */
    private static void loadPropertiesFile(File file, Properties target) {
        if (file == null || !file.isFile()) {
            return;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            target.load(fis);
        } catch (IOException ignored) { /* silently skip */ }
    }

    /**
     * Parses an XML file into a {@link Document}.
     *
     * @return the parsed document, or {@code null} if the file is missing, empty, or not valid XML
     */
    private static Document parseDocument(File file)
            throws ParserConfigurationException, IOException {
        if (file == null || !file.isFile() || file.length() == 0) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            return newDocumentBuilder().parse(fis);
        } catch (SAXException e) {
            return null; // not valid XML — treat as absent
        }
    }

    /**
     * Creates a new {@link XPath} instance per call.
     * {@code XPath} is not thread-safe per the JAXP spec; one instance per use is required.
     */
    private static XPath newXPath() {
        return XPathFactory.newInstance().newXPath();
    }

    /** Creates a new secure, non-validating {@link DocumentBuilder} per call. */
    private static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar",  false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",           true);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",        false);
        f.setFeature("http://xml.org/sax/features/external-general-entities",          false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,                           true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    /**
     * Paths extracted from a {@code liberty-plugin-config.xml} document.
     * All fields may be {@code null} when the corresponding element is absent.
     */
    private static final class PluginConfig {
        final File installDirectory;
        final File userDirectory;
        final File serverDirectory;
        final File serverXmlFile;           // <configFile>
        final File bootstrapPropertiesFile; // <bootstrapPropertiesFile>
        final File serverEnvFile;           // <serverEnv>

        private PluginConfig(File installDirectory, File userDirectory, File serverDirectory,
                File serverXmlFile, File bootstrapPropertiesFile, File serverEnvFile) {
            this.installDirectory       = installDirectory;
            this.userDirectory          = userDirectory;
            this.serverDirectory        = serverDirectory;
            this.serverXmlFile          = serverXmlFile;
            this.bootstrapPropertiesFile = bootstrapPropertiesFile;
            this.serverEnvFile          = serverEnvFile;
        }

        /** Reads a {@code PluginConfig} from an already-parsed {@code liberty-plugin-config.xml}. */
        static PluginConfig from(Document doc) throws XPathExpressionException {
            return new PluginConfig(
                    toFile(textOf(doc, "/liberty-plugin-config/installDirectory")),
                    toFile(textOf(doc, "/liberty-plugin-config/userDirectory")),
                    toFile(textOf(doc, "/liberty-plugin-config/serverDirectory")),
                    toFile(textOf(doc, "/liberty-plugin-config/configFile")),
                    toFile(textOf(doc, "/liberty-plugin-config/bootstrapPropertiesFile")),
                    toFile(textOf(doc, "/liberty-plugin-config/serverEnv")));
        }

        private static String textOf(Document doc, String xpath) throws XPathExpressionException {
            Element el = (Element) newXPath()
                    .compile(xpath).evaluate(doc, XPathConstants.NODE);
            if (el == null) {
                return null;
            }
            String text = el.getTextContent();
            return (text == null || text.trim().isEmpty()) ? null : text.trim();
        }

        private static File toFile(String path) {
            return (path != null) ? new File(path) : null;
        }
    }
}
