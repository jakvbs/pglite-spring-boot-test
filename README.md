# pglite-spring-boot-test

Spring Boot test auto-configuration that launches an in-memory PostgreSQL compatible endpoint backed by [PGlite] (WebAssembly) and exposes it via PGWire. The module injects a single-connection `DataSource` so integration tests can run without Docker, Testcontainers, or native Postgres binaries.

- Single active connection (PGlite limitation)
- No SSL; JDBC URL enforces `sslmode=disable` and `preferQueryMode=simple`
- Bundled Node.js helper script + dependencies (requires Node 18+ on the host)

## Quick start

Add dependency (test scope recommended):

```xml
<dependency>
  <groupId>io.github.jakvbs</groupId>
  <artifactId>pglite-spring-boot-test</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
```

Prerequisites on the machine running the tests:

- **Windows:** no external Node installation required. The starter ships with a bundled Node.js 24.11.0 runtime (MIT license) and uses it automatically if `node` is not on `PATH`.
- **macOS / Linux:** Node.js ≥ 18 must be available on `PATH` (or provide an explicit command via `pglite.node-command`).
- No manual `npm install` is necessary – the helper runtime (with `@electric-sql/pglite` + `pg-gateway`) is embedded inside the JAR and extracted to a temporary directory at start-up.

Enable in tests with either:

- Property: `pglite.enabled=true` (e.g. `@TestPropertySource` or profile file), or
- Initializer: `@ContextConfiguration(initializers = PgliteContextInitializer.class)`

Optional properties (prefix `pglite.`):

- `enabled` (boolean) – default `false`
- `host` – default `127.0.0.1`
- `port` – default `0` (auto-assign)
- `node-command` – semicolon separated list of Node binaries to try (e.g. `"C:\\Program Files\\nodejs\\node.exe";node`)
- `startup-timeout` – default `30s`
- `username` – default `postgres`
- `password` – default empty
- `database` – default `postgres`
- `jdbc-params` – default `sslmode=disable&preferQueryMode=simple`
- `path-prepend` – semicolon separated directories prepended to the `PATH` seen by the helper process

## Notes

- The DataSource is built on `SingleConnectionDataSource` with `suppressClose=true` and `autoCommit=false`, wrapped in `TransactionAwareDataSourceProxy`.
- Do not use a connection pool; if present, keep max pool size = 1.
- Long transactions block all DB work; keep them short for tests.
- Exit code 9009 indicates the Node executable was not found – set `pglite.node-command` or ensure `node` is on PATH.
- Bundled Windows runtime includes Node.js 24.11.0 (MIT); the upstream LICENSE is shipped alongside the executable inside the packaged helper.

[PGlite]: https://github.com/electric-sql/pglite
