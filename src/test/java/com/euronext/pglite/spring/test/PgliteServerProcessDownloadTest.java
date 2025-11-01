package com.euronext.pglite.spring.test;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisabledOnOs(OS.WINDOWS)
class PgliteServerProcessDownloadTest {

    private HttpServer server;
    private int port;
    private byte[] runtimeZip;

    @BeforeEach
    void setUp() throws IOException {
        runtimeZip = buildRuntimeZip();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, runtimeZip.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(runtimeZip);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void downloadsArchiveWhenChecksumMatches() throws Exception {
        String checksum = computeSha256Hex(runtimeZip);

        try (PgliteServerProcess process = new PgliteServerProcess(
                "127.0.0.1",
                0,
                Duration.ofSeconds(10),
                null,
                null,
                "http://localhost:" + port + "/runtime-{os}-{arch}.zip",
                Files.createTempDirectory("pglite-cache").toString(),
                checksum,
                "postgres",
                "",
                PgliteProperties.LogLevel.WARNING
        )) {
            Path runtimeRoot = Files.createTempDirectory("pglite-runtime-root");
            Path nodeBinary = invokeEnsureRuntime(process, runtimeRoot);
            assertThat(nodeBinary).isNotNull();
            assertThat(Files.isRegularFile(nodeBinary)).isTrue();
            assertThat(nodeBinary.toString()).contains(currentOsToken()).contains(currentArchToken());
        }
    }

    @Test
    void failsWhenChecksumMismatch() throws Exception {
        try (PgliteServerProcess process = new PgliteServerProcess(
                "127.0.0.1",
                0,
                Duration.ofSeconds(10),
                null,
                null,
                "http://localhost:" + port + "/runtime-{os}-{arch}.zip",
                Files.createTempDirectory("pglite-cache").toString(),
                "0".repeat(64),
                "postgres",
                "",
                PgliteProperties.LogLevel.WARNING
        )) {
            Path runtimeRoot = Files.createTempDirectory("pglite-runtime-root");
            assertThatThrownBy(() -> invokeEnsureRuntime(process, runtimeRoot))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to download platform runtime")
                    .hasCauseInstanceOf(IOException.class)
                    .satisfies(ex -> assertThat(ex.getCause().getMessage()).contains("Checksum mismatch"));
        }
    }

    private Path invokeEnsureRuntime(PgliteServerProcess process, Path runtimeRoot) throws Exception {
        Method method = PgliteServerProcess.class.getDeclaredMethod("ensureRuntimeForCurrentPlatform", Path.class);
        method.setAccessible(true);
        try {
            return (Path) method.invoke(process, runtimeRoot);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    private String currentOsToken() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        }
        return "linux";
    }

    private String currentArchToken() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        return "x64";
    }

    private byte[] buildRuntimeZip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("bin/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("bin/node"));
            zip.write("#!/bin/sh\necho node".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private String computeSha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
