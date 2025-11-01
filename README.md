# pglite-spring-boot-test

Spring Boot auto-configuration that launches an in-memory PostgreSQL-compatible endpoint backed by [PGlite] (WebAssembly). It exposes PGWire via the bundled helper runtime and injects a single-connection `DataSource`, so integration tests and local profiles can run without Docker, Testcontainers, or native Postgres binaries.

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
- `runtime-download-url-template` – optional template (e.g. `https://example.com/runtime-{os}-{arch}.zip`) for platform-specific helper bundles (`{os}` = `linux`/`darwin`, `{arch}` = `x64`/`arm64`)
- `runtime-cache-dir` – optional directory used to cache downloaded bundles
- `log-level` – helper verbosity (`DEBUG`, `INFO`, `WARNING`, `ERROR`; default `WARNING`)

## Notes

- The DataSource is built on `SingleConnectionDataSource` with `suppressClose=true` and `autoCommit=false`, wrapped in `TransactionAwareDataSourceProxy`.
- Do not use a connection pool; if present, keep max pool size = 1.
- Long transactions block all DB work; keep them short for tests.
- Exit code 9009 indicates the Node executable was not found – set `pglite.node-command` or ensure `node` is on PATH.
- Bundled Windows runtime includes Node.js 24.11.0 (MIT); the upstream LICENSE is shipped alongside the executable inside the packaged helper.

## Example

Spring Boot integration test using the starter:

```java
@SpringBootTest
@ContextConfiguration(initializers = PgliteContextInitializer.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountRepositoryIT {

    @Autowired
    AccountRepository repository;

    @Test
    void savesAndLoadsAccounts() {
        Account saved = repository.save(new Account("ACC-1"));
        assertThat(repository.findById(saved.getId())).isPresent();
    }
}
```

`application-test.properties` (or whichever profile backs the test) should enable the starter:

```properties
pglite.enabled=true
pglite.node-command=node
spring.datasource.hikari.maximum-pool-size=1
spring.datasource.hikari.minimum-idle=0
```

With that configuration, the initializer spins up the embedded PGWire server before the Spring context starts, and the starter supplies a single-connection `DataSource` that points at the in-memory PGlite instance.

## Optional runtime bundles

The embedded ZIP ships with:

- `node_modules/` containing `@electric-sql/pglite` + `pg-gateway`
- `node-win-x64/` with Node.js 24.11.0 for Windows (no external install required on Windows hosts)

For macOS/Linux you can either keep relying on `node` present in `PATH` or provide additional runtime bundles by setting `pglite.runtime-download-url-template`. The template should evaluate to a ZIP that contains a Node distribution for the given platform together with the helper `node_modules`. Recommended naming convention:

```
https://example.com/pglite-runtime-{os}-{arch}.zip
```

where `{os}` is `linux` or `darwin`, and `{arch}` is `x64` or `arm64`. The starter downloads the archive on first use (into the optional `runtime-cache-dir` or the system temp), unpacks it alongside the helper, and adds the contained `bin/node` to the candidate list.

## Development

- `PgliteServerProcessIntegrationTest` exercises Liquibase migrations, prepared statements, and the single-connection JDBC path exposed by the Node helper.
- `PgliteAutoConfigurationIntegrationTest` boots the auto-configuration through `ApplicationContextRunner` to verify the Spring context wiring and Liquibase bootstrap.
- GitHub Actions runs `mvn test` on Ubuntu, macOS, and Windows runners (see `.github/workflows/ci.yml`) to guard platform-specific regressions.

[PGlite]: https://github.com/electric-sql/pglite

## Acknowledgements

- [PGlite](https://github.com/electric-sql/pglite) – the WebAssembly PostgreSQL engine powering the starter.
- [pg-gateway](https://github.com/supabase-community/pg-gateway) – lightweight PGWire gateway leveraged by the helper runtime.
