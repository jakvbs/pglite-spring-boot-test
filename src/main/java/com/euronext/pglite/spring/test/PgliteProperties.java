package com.euronext.pglite.spring.test;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("pglite")
public class PgliteProperties {
    /** Enable auto-configuration. */
    private boolean enabled = false;

    /** Host to bind the PGWire TCP listener. */
    private String host = "127.0.0.1";

    /** Port to bind; 0 means auto-pick a free port. */
    private int port = 0;

    /** Node executable command, e.g. "node" or "C:\\Program Files\\nodejs\\node.exe". */
    private String nodeCommand;

    /** Startup timeout for the helper process. */
    private Duration startupTimeout = Duration.ofSeconds(30);

    /** Username for JDBC. PGlite accepts postgres with empty password. */
    private String username = "postgres";

    /** Password for JDBC. */
    private String password = "";

    /** Extra path entries (semicolon separated) to prepend to PATH for the helper. */
    private String pathPrepend;

    /** Extra JDBC URL params appended after '?', without leading '&'. */
    private String jdbcParams = "sslmode=disable&preferQueryMode=simple";

    /** Optional fixed database name (defaults to 'postgres'). */
    private String database = "postgres";

    /** Optional template for downloading a platform-specific runtime archive (e.g. https://.../runtime-{os}-{arch}.zip). */
    private String runtimeDownloadUrlTemplate;

    /** Optional directory used to cache downloaded runtimes (defaults to system temp). */
    private String runtimeCacheDir;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getNodeCommand() { return nodeCommand; }
    public void setNodeCommand(String nodeCommand) { this.nodeCommand = nodeCommand; }
    public Duration getStartupTimeout() { return startupTimeout; }
    public void setStartupTimeout(Duration startupTimeout) { this.startupTimeout = startupTimeout; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getPathPrepend() { return pathPrepend; }
    public void setPathPrepend(String pathPrepend) { this.pathPrepend = pathPrepend; }
    public String getJdbcParams() { return jdbcParams; }
    public void setJdbcParams(String jdbcParams) { this.jdbcParams = jdbcParams; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getRuntimeDownloadUrlTemplate() { return runtimeDownloadUrlTemplate; }
    public void setRuntimeDownloadUrlTemplate(String runtimeDownloadUrlTemplate) { this.runtimeDownloadUrlTemplate = runtimeDownloadUrlTemplate; }
    public String getRuntimeCacheDir() { return runtimeCacheDir; }
    public void setRuntimeCacheDir(String runtimeCacheDir) { this.runtimeCacheDir = runtimeCacheDir; }
}
