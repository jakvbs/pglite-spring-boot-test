package com.euronext.pglite.spring.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StreamUtils;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages a single py-pglite helper process that exposes PGWire over TCP.
 */
final class PgliteServerProcess implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(PgliteServerProcess.class);

    private final String host;
    private final int configuredPort;
    private final Duration startupTimeout;
    private final String pythonExe;
    private final String pathPrepend;

    private volatile int port;
    private final AtomicReference<Process> processRef = new AtomicReference<>();
    private final List<String> outputBuffer = Collections.synchronizedList(new ArrayList<>());
    private ExecutorService ioPool;

    PgliteServerProcess(String host, int configuredPort, Duration startupTimeout, String pythonExe, String pathPrepend) {
        this.host = Objects.requireNonNull(host);
        this.configuredPort = configuredPort;
        this.startupTimeout = Objects.requireNonNull(startupTimeout);
        this.pythonExe = pythonExe;
        this.pathPrepend = pathPrepend;
    }

    void start() {
        if (processRef.get() != null) return;

        int portToUse = configuredPort > 0 ? configuredPort : findAvailablePort();
        this.port = portToUse;

        Path script = extractHelperScript();
        List<String[]> commandCandidates = buildPythonCommandCandidates(script);

        List<String> attemptErrors = new ArrayList<>();
        for (String[] candidate : commandCandidates) {
            String joined = String.join(" ", candidate);
            try {
                ProcessBuilder pb = new ProcessBuilder(candidate);
                pb.redirectErrorStream(true);
                Map<String, String> env = pb.environment();
                env.put("PGLITE_PORT", Integer.toString(portToUse));
                env.put("PGLITE_HOST", host);
                if (pathPrepend != null && !pathPrepend.isBlank()) {
                    String sep = File.pathSeparator;
                    env.put("PATH", pathPrepend + sep + env.getOrDefault("PATH", ""));
                }
                Process p = pb.start();
                this.ioPool = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "pglite-io");
                    t.setDaemon(true);
                    return t;
                });
                CountDownLatch ready = new CountDownLatch(1);
                AtomicReference<Throwable> readErr = new AtomicReference<>();
                this.ioPool.submit(() -> readLoop(p.getInputStream(), ready, readErr));
                awaitReady(ready, readErr, p);
                this.processRef.set(p);
                Runtime.getRuntime().addShutdownHook(new Thread(this::safeStop));
                log.info("PGlite started on {}:{} via {}", host, portToUse, joined);
                return;
            } catch (IOException ex) {
                int code = -1;
                try { code = processRef.get() != null && processRef.get().isAlive() ? processRef.get().exitValue() : -1; } catch (Exception ignored) {}
                attemptErrors.add(joined + " -> " + ex.getClass().getSimpleName() + ": " + ex.getMessage() + (code >= 0 ? (", exit=" + code) : ""));
            } catch (IllegalStateException ex) {
                attemptErrors.add(joined + " -> " + ex.getMessage());
            }
        }
        String joined = String.join(" | ", attemptErrors);
        throw new IllegalStateException("Failed to start py-pglite. Attempts: " + joined);
    }

    private void awaitReady(CountDownLatch latch, AtomicReference<Throwable> readErr, Process p) {
        boolean ok;
        try { ok = latch.await(startupTimeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            safeDestroy(p);
            throw new IllegalStateException("Interrupted while waiting for PGlite READY");
        }
        if (!ok) {
            safeDestroy(p);
            throw new IllegalStateException("Timed out waiting for PGlite. Output: " + joinOutput());
        }
        if (readErr.get() != null) {
            safeDestroy(p);
            throw new IllegalStateException("Failed to read PGlite output: " + readErr.get().getMessage() + "; Output: " + joinOutput(), readErr.get());
        }
        if (!p.isAlive()) {
            int exit = p.exitValue();
            String hint = exit == 9009 ? " (Windows: python not found on PATH)" : "";
            throw new IllegalStateException("py-pglite process exited with code " + exit + hint + ". Output: " + joinOutput());
        }
    }

    private void readLoop(InputStream in, CountDownLatch ready, AtomicReference<Throwable> readErr) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                appendOutput(line);
                if (line.contains("\"event\"")) {
                    if (line.contains("\"READY\"")) {
                        ready.countDown();
                    } else if (line.contains("\"ERROR\"")) {
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
        if (line == null) return;
        synchronized (outputBuffer) {
            outputBuffer.add(line);
            if (outputBuffer.size() > 200) outputBuffer.remove(0);
        }
    }

    private String joinOutput() {
        StringBuilder sb = new StringBuilder();
        synchronized (outputBuffer) {
            for (String l : outputBuffer) {
                if (!sb.isEmpty()) sb.append(" | ");
                sb.append(l);
            }
        }
        return sb.toString();
    }

    private void safeDestroy(Process p) {
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    private void safeStop() {
        try { close(); } catch (IOException ignored) {}
    }

    @Override
    public void close() throws IOException {
        Process p = processRef.getAndSet(null);
        if (p != null) {
            p.destroy();
            try { p.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (p.isAlive()) p.destroyForcibly();
        }
        if (ioPool != null) {
            ioPool.shutdownNow();
            try { ioPool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    String host() { return host; }
    int port() { return port; }

    String jdbcUrl(String database, String params) {
        String qp = (params == null || params.isBlank()) ? "" : ("?" + params);
        return "jdbc:postgresql://" + host + ":" + port + "/" + database + qp;
    }

    private int findAvailablePort() {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getByName(host))) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate a free TCP port for PGlite", e);
        }
    }

    private List<String[]> buildPythonCommandCandidates(Path script) {
        List<String[]> out = new ArrayList<>();
        // explicit from props
        String explicit = this.pythonExe != null && !this.pythonExe.isBlank() ? this.pythonExe : System.getenv("PYTHON_EXE");
        if (explicit != null && !explicit.isBlank()) {
            out.add(command(explicit, script));
        }
        if (isWindows()) {
            out.add(new String[] { "py", "-3", "-u", script.toString() });
            out.add(new String[] { "python", "-u", script.toString() });
            out.add(new String[] { "python3", "-u", script.toString() });
        } else {
            out.add(new String[] { "python3", "-u", script.toString() });
            out.add(new String[] { "python", "-u", script.toString() });
        }
        return out;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private String[] command(String raw, Path script) {
        List<String> parts = parseCommand(raw);
        parts.add("-u");
        parts.add(script.toString());
        return parts.toArray(new String[0]);
    }

    private static List<String> parseCommand(String cmd) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false; char qc = 0;
        for (int i=0;i<cmd.length();i++) {
            char c = cmd.charAt(i);
            if (inQ) {
                if (c == qc) inQ = false; else if (c=='\\' && i+1<cmd.length() && cmd.charAt(i+1)==qc) { cur.append(qc); i++; } else cur.append(c);
            } else {
                if (Character.isWhitespace(c)) { if (!cur.isEmpty()) { parts.add(cur.toString()); cur.setLength(0);} }
                else if (c=='"' || c=='\'') { inQ = true; qc = c; }
                else cur.append(c);
            }
        }
        if (!cur.isEmpty()) parts.add(cur.toString());
        return parts;
    }

    private Path extractHelperScript() {
        try {
            Path dir = Files.createTempDirectory("pglite-helper-");
            Path script = dir.resolve("start.py");
            try (InputStream in = PgliteServerProcess.class.getResourceAsStream("/pglite/start.py")) {
                if (in == null) throw new FileNotFoundException("Resource /pglite/start.py not found in JAR");
                Files.copy(in, script);
            }
            // Best effort on POSIX
            try { script.toFile().setExecutable(true); } catch (Throwable ignored) {}
            return script;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to extract helper script", e);
        }
    }
}
