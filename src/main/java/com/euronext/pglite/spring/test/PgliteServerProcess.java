package com.euronext.pglite.spring.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages a single Node-based helper process that exposes PGlite over PGWire.
 */
final class PgliteServerProcess implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(PgliteServerProcess.class);
    private static final String RUNTIME_ARCHIVE_RESOURCE = "/pglite/runtime.zip";
    private static final String START_SCRIPT_RESOURCE = "/pglite/start.mjs";
    private static final String PACKAGE_JSON_RESOURCE = "/pglite/package.json";
    private static final String PACKAGE_LOCK_RESOURCE = "/pglite/package-lock.json";

    private final String host;
    private final int configuredPort;
    private final Duration startupTimeout;
    private final String nodeCommand;
    private final String pathPrepend;
    private final String runtimeDownloadUrlTemplate;
    private final String runtimeCacheDir;
    private final String jdbcUsername;
    private final String jdbcPassword;

    private volatile int port;
    private final AtomicReference<Process> processRef = new AtomicReference<>();
    private final List<String> outputBuffer = Collections.synchronizedList(new ArrayList<>());
    private ExecutorService ioPool;
    private Path runtimeDir;

    PgliteServerProcess(String host, int configuredPort, Duration startupTimeout,
                        String nodeCommand, String pathPrepend,
                        String runtimeDownloadUrlTemplate, String runtimeCacheDir,
                        String jdbcUsername, String jdbcPassword) {
        this.host = Objects.requireNonNull(host);
        this.configuredPort = configuredPort;
        this.startupTimeout = Objects.requireNonNull(startupTimeout);
        this.nodeCommand = nodeCommand;
        this.pathPrepend = pathPrepend;
        this.runtimeDownloadUrlTemplate = runtimeDownloadUrlTemplate;
        this.runtimeCacheDir = runtimeCacheDir;
        this.jdbcUsername = jdbcUsername;
        this.jdbcPassword = jdbcPassword;
    }

    void start() {
        if (processRef.get() != null) {
            return;
        }

        runtimeDir = extractRuntime();
        Path script = runtimeDir.resolve("start.mjs");
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("Missing PGlite helper script at " + script);
        }

        int portToUse = configuredPort > 0 ? configuredPort : findAvailablePort();
        this.port = portToUse;

        List<String[]> commandCandidates = buildNodeCommandCandidates(script);
        List<String> attemptErrors = new ArrayList<>();

        for (String[] candidate : commandCandidates) {
            String joined = String.join(" ", candidate);
            try {
                ProcessBuilder pb = new ProcessBuilder(candidate);
                pb.redirectErrorStream(true);
                pb.directory(runtimeDir.toFile());

                Map<String, String> env = pb.environment();
                env.put("PGLITE_PORT", Integer.toString(portToUse));
                env.put("PGLITE_HOST", host);
                env.put("PGLITE_USERS_JSON", buildUsersJson());
                if (pathPrepend != null && !pathPrepend.isBlank()) {
                    env.put("PATH", pathPrepend + File.pathSeparator + env.getOrDefault("PATH", ""));
                }

                Process process = pb.start();
                this.ioPool = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "pglite-io");
                    t.setDaemon(true);
                    return t;
                });
                CountDownLatch ready = new CountDownLatch(1);
                AtomicReference<Throwable> readErr = new AtomicReference<>();
                ioPool.submit(() -> readLoop(process.getInputStream(), ready, readErr));
                awaitReady(ready, readErr, process);
                this.processRef.set(process);
                Runtime.getRuntime().addShutdownHook(new Thread(this::safeStop));
                log.info("PGlite started on {}:{} via {}", host, portToUse, joined);
                return;
            } catch (IOException ex) {
                attemptErrors.add(joined + " -> " + ex.getMessage());
            } catch (IllegalStateException ex) {
                attemptErrors.add(joined + " -> " + ex.getMessage());
            }
        }

        throw new IllegalStateException("Failed to start Node PGlite helper. Attempts: " + String.join(" | ", attemptErrors));
    }

    private Path extractRuntime() {
        try (InputStream in = PgliteServerProcess.class.getResourceAsStream(RUNTIME_ARCHIVE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Runtime archive " + RUNTIME_ARCHIVE_RESOURCE + " not found on classpath");
            }
            Path dir = Files.createTempDirectory("pglite-node-runtime");
            try (ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path target = dir.resolve(entry.getName()).normalize();
                    if (!target.startsWith(dir)) {
                        throw new IOException("Zip entry outside target dir: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                        if (isExecutable(entry.getName())) {
                            target.toFile().setExecutable(true, false);
                        }
                    }
                }
            }
            copyResource(START_SCRIPT_RESOURCE, dir.resolve("start.mjs"));
            copyResource(PACKAGE_JSON_RESOURCE, dir.resolve("package.json"));
            copyResource(PACKAGE_LOCK_RESOURCE, dir.resolve("package-lock.json"));
            return dir;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to extract embedded PGlite runtime", ex);
        }
    }

    private void copyResource(String resource, Path target) throws IOException {
        try (InputStream src = PgliteServerProcess.class.getResourceAsStream(resource)) {
            if (src == null) {
                throw new IllegalStateException("Resource " + resource + " not found on classpath");
            }
            Files.createDirectories(target.getParent());
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean isExecutable(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".sh") || lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    private void awaitReady(CountDownLatch latch, AtomicReference<Throwable> readErr, Process process) {
        boolean ok;
        try {
            ok = latch.await(startupTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            safeDestroy(process);
            throw new IllegalStateException("Interrupted while waiting for PGlite READY", ex);
        }

        if (!ok) {
            safeDestroy(process);
            throw new IllegalStateException("Timed out waiting for PGlite. Output: " + joinOutput());
        }

        if (readErr.get() != null) {
            safeDestroy(process);
            throw new IllegalStateException("Failed to read PGlite output. Output: " + joinOutput(), readErr.get());
        }

        if (!process.isAlive()) {
            String hint = process.exitValue() == 9009 ? " (Windows: node command not found)" : "";
            throw new IllegalStateException("PGlite helper exited with code " + process.exitValue() + hint + ". Output: " + joinOutput());
        }
    }

    private void readLoop(InputStream inputStream, CountDownLatch ready, AtomicReference<Throwable> readErr) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendOutput(line);
                // Parse simple JSON line with {"event": "READY"|"ERROR", ...}
                String trimmed = line.trim();
                if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))) {
                    continue;
                }
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.contains("\"event\"")) {
                    if (lower.contains("\"ready\"")) {
                        ready.countDown();
                    } else if (lower.contains("\"error\"")) {
                        ready.countDown();
                    }
                }
            }
        } catch (IOException ex) {
            readErr.compareAndSet(null, ex);
            ready.countDown();
        }
    }

    private void appendOutput(String line) {
        synchronized (outputBuffer) {
            outputBuffer.add(line);
            if (outputBuffer.size() > 200) {
                outputBuffer.remove(0);
            }
        }
    }

    private String joinOutput() {
        StringBuilder sb = new StringBuilder();
        synchronized (outputBuffer) {
            for (String entry : outputBuffer) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(entry);
            }
        }
        return sb.toString();
    }

    private void safeDestroy(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private void safeStop() {
        try {
            close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() throws IOException {
        Process process = processRef.getAndSet(null);
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        if (ioPool != null) {
            ioPool.shutdownNow();
            try {
                ioPool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        if (runtimeDir != null) {
            try {
                deleteRecursively(runtimeDir);
            } catch (IOException ex) {
                log.debug("Failed to clean runtime dir {}: {}", runtimeDir, ex.getMessage());
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ex) {
                            log.debug("Failed to delete {}: {}", p, ex.getMessage());
                        }
                    });
        }
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    String jdbcUrl(String database, String params) {
        String qp = (params == null || params.isBlank()) ? "" : ("?" + params);
        return "jdbc:postgresql://" + host + ":" + port + "/" + database + qp;
    }

    private List<String[]> buildNodeCommandCandidates(Path script) {
        List<List<String>> ordered = new ArrayList<>();

        if (isWindows()) {
            Path embeddedNode = runtimeDir.resolve("node-win-x64").resolve("node.exe");
            if (Files.isRegularFile(embeddedNode)) {
                ordered.add(List.of(embeddedNode.toString()));
            }
        } else {
            Path downloadedNode = ensureRuntimeForCurrentPlatform(runtimeDir);
            if (downloadedNode != null) {
                ordered.add(List.of(downloadedNode.toString()));
            }
        }

        if (nodeCommand != null && !nodeCommand.isBlank()) {
            ordered.addAll(parseCommands(nodeCommand));
        }

        ordered.add(List.of("node"));
        ordered.add(List.of("node.exe"));
        ordered.add(List.of("nodejs"));

        Set<List<String>> unique = new LinkedHashSet<>(ordered);

        List<String[]> result = new ArrayList<>(unique.size());
        for (List<String> base : unique) {
            List<String> full = new ArrayList<>(base.size() + 1);
            full.addAll(base);
            full.add(script.toString());
            result.add(full.toArray(new String[0]));
        }
        return result;
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    private List<List<String>> parseCommands(String raw) {
        List<List<String>> commands = new ArrayList<>();
        for (String candidate : raw.split(";")) {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            commands.add(parseCommand(trimmed));
        }
        return commands;
    }

    private List<String> parseCommand(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false;
                } else if (c == '\\' && i + 1 < command.length() && command.charAt(i + 1) == quoteChar) {
                    current.append(quoteChar);
                    i++;
                } else {
                    current.append(c);
                }
            } else {
                if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        parts.add(current.toString());
                        current.setLength(0);
                    }
                } else if (c == '"' || c == '\'') {
                    inQuotes = true;
                    quoteChar = c;
                } else {
                    current.append(c);
                }
            }
        }
        if (inQuotes) {
            throw new IllegalStateException("Unterminated quotes in command: " + command);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private Path ensureRuntimeForCurrentPlatform(Path runtimeDir) {
        if (runtimeDownloadUrlTemplate == null || runtimeDownloadUrlTemplate.isBlank()) {
            return null;
        }
        if (isWindows()) {
            return null;
        }

        String osToken = detectOsToken();
        String archToken = detectArchToken();
        Path targetDir = runtimeDir.resolve("node-" + osToken + "-" + archToken);

        Path existing = resolveNodeExecutable(targetDir);
        if (existing != null) {
            setExecutable(existing);
            return existing;
        }

        try {
            Path cacheBase = runtimeCacheDir != null && !runtimeCacheDir.isBlank()
                    ? Path.of(runtimeCacheDir)
                    : Path.of(System.getProperty("java.io.tmpdir"), "pglite-runtime-cache");
            Files.createDirectories(cacheBase);

            String url = runtimeDownloadUrlTemplate
                    .replace("{os}", osToken)
                    .replace("{arch}", archToken);

            Path archivePath = cacheBase.resolve("runtime-" + osToken + "-" + archToken + ".zip");
            downloadIfNeeded(url, archivePath);

            Path extractedDir = cacheBase.resolve("runtime-" + osToken + "-" + archToken);
            unzip(archivePath, extractedDir);

            if (Files.exists(targetDir)) {
                deleteRecursively(targetDir);
            }
            copyDirectory(extractedDir, targetDir);

            Path nodeBinary = resolveNodeExecutable(targetDir);
            if (nodeBinary == null) {
                throw new IllegalStateException("Downloaded runtime from " + url + " does not contain a Node executable");
            }
            setExecutable(nodeBinary);
            return nodeBinary;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to download platform runtime", ex);
        }
    }

    private void downloadIfNeeded(String urlString, Path destination) throws IOException {
        if (Files.isRegularFile(destination)) {
            return;
        }
        log.info("Downloading PGlite runtime from {}", urlString);
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "pglite-spring-boot-test");
        int status = connection.getResponseCode();
        if (status >= 400) {
            throw new IOException("Failed to download " + urlString + ": HTTP " + status);
        }
        Files.createDirectories(destination.getParent());
        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }
    }

    private void unzip(Path zipFile, Path destination) throws IOException {
        if (Files.exists(destination)) {
            deleteRecursively(destination);
        }
        Files.createDirectories(destination);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination)) {
                    throw new IOException("Zip entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void copyDirectory(Path source, Path destination) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private Path resolveNodeExecutable(Path baseDir) {
        if (baseDir == null || !Files.exists(baseDir)) {
            return null;
        }
        Path candidate = baseDir.resolve("bin").resolve(isWindows() ? "node.exe" : "node");
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        try (Stream<Path> paths = Files.walk(baseDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.equals("node") || name.equals("node.exe");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to locate node executable", ex);
        }
    }

    private void setExecutable(Path path) {
        try {
            File file = path.toFile();
            if (!file.canExecute()) {
                // make executable for owner/group/others when possible
                file.setExecutable(true, false);
            }
        } catch (SecurityException ex) {
            log.debug("Unable to mark {} as executable: {}", path, ex.getMessage());
        }
    }

    private String detectOsToken() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        }
        if (os.contains("win")) {
            return "win";
        }
        return "linux";
    }

    private String detectArchToken() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        return "x64";
    }

    private int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName(host))) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate TCP port for PGlite", ex);
        }
    }

    private String buildUsersJson() {
        Map<String, String> users = new LinkedHashMap<>();
        users.put("postgres", "");
        if (jdbcUsername != null && !jdbcUsername.isBlank()) {
            users.put(jdbcUsername, jdbcPassword != null ? jdbcPassword : "");
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : users.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escapeJson(entry.getKey())).append('"')
                    .append(':')
                    .append('"').append(escapeJson(entry.getValue())).append('"');
        }
        json.append('}');
        return json.toString();
    }

    private String escapeJson(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
