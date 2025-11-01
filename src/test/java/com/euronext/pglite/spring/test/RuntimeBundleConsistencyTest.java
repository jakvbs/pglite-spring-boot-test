package com.euronext.pglite.spring.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeBundleConsistencyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path runtimeZip;

    @BeforeEach
    void copyRuntime() throws IOException {
        try (InputStream in = PgliteServerProcess.class.getResourceAsStream("/pglite/runtime.zip")) {
            if (in == null) {
                throw new IllegalStateException("runtime.zip resource missing");
            }
            runtimeZip = Files.createTempFile("pglite-runtime", ".zip");
            Files.copy(in, runtimeZip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @AfterEach
    void cleanup() throws IOException {
        if (runtimeZip != null) {
            Files.deleteIfExists(runtimeZip);
        }
    }

    @Test
    void packagedRuntimeMatchesDeclaredLockfile() throws Exception {
        Map<String, String> expectedVersions = readExpectedModuleVersions();
        try (ZipFile zipFile = new ZipFile(runtimeZip.toFile())) {
            for (Map.Entry<String, String> entry : expectedVersions.entrySet()) {
                String modulePath = entry.getKey();
                String expectedVersion = entry.getValue();
                String zipPath = modulePath + "/package.json";

                ZipEntry zipEntry = zipFile.getEntry(zipPath);
                assertThat(zipEntry)
                        .as("runtime.zip should contain %s", zipPath)
                        .isNotNull();

                JsonNode packageJson;
                try (InputStream moduleIn = zipFile.getInputStream(zipEntry)) {
                    packageJson = objectMapper.readTree(moduleIn);
                }

                JsonNode versionNode = packageJson.get("version");
                assertThat(versionNode)
                        .as("package.json for %s must declare version", modulePath)
                        .isNotNull();

                String actualVersion = versionNode.asText();
                assertThat(actualVersion)
                        .as("Version mismatch for %s", modulePath)
                        .isEqualTo(expectedVersion);
            }
        }
    }

    @Test
    void packagedRuntimeDoesNotContainUnexpectedRootPackageJson() throws Exception {
        try (ZipFile zipFile = new ZipFile(runtimeZip.toFile())) {
            assertThat(zipFile.getEntry("package.json"))
                    .as("runtime.zip should not embed top-level package.json; use classpath copy instead")
                    .isNull();
        }
    }

    private Map<String, String> readExpectedModuleVersions() throws IOException {
        try (InputStream lockStream = PgliteServerProcess.class.getResourceAsStream("/pglite/package-lock.json")) {
            if (lockStream == null) {
                throw new IllegalStateException("package-lock.json resource missing");
            }
            JsonNode lockRoot = objectMapper.readTree(lockStream);
            JsonNode packagesNode = lockRoot.get("packages");
            if (packagesNode == null || !packagesNode.isObject()) {
                throw new IllegalStateException("package-lock.json missing 'packages' object");
            }

            Map<String, String> versions = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = packagesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String path = entry.getKey();
                if (!path.toLowerCase(Locale.ROOT).startsWith("node_modules/")) {
                    continue;
                }
                JsonNode versionNode = entry.getValue().get("version");
                if (versionNode == null) {
                    continue;
                }
                versions.put(path, versionNode.asText());
            }

            assertThat(versions)
                    .as("Expected at least one dependency in package-lock.json")
                    .isNotEmpty();

            return versions;
        }
    }
}
