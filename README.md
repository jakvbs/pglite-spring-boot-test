# pglite-spring-boot-test

Lightweight Spring Boot test auto‑configuration that starts an in‑memory Postgres via [py-pglite] and exposes PGWire over TCP. It injects a single‑connection `DataSource` suitable for unit/integration tests without Docker, Testcontainers, or embedded binaries.

- Single active connection (PGlite limitation)
- No SSL; JDBC URL forces `sslmode=disable` and `preferQueryMode=simple`
- Cross‑platform Python launch (Windows `py -3`, `python`, `python3`)

## Quick start

Add dependency (test scope recommended):

```
<dependency>
  <groupId>com.euronext.pglite</groupId>
  <artifactId>pglite-spring-boot-test</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

Ensure Python 3 and py‑pglite are available on the machine where tests run:

```
python -m pip install py-pglite
```

Enable in tests with either:

- Property: `pglite.enabled=true` (e.g., `@TestPropertySource` or profile file), or
- Initializer: `@ContextConfiguration(initializers = PgliteContextInitializer.class)`

Optional properties (prefix `pglite.`):

- `enabled` (bool) – default `false`
- `host` – default `127.0.0.1`
- `port` – default `0` (auto‑assign)
- `python-exe` – explicit interpreter, e.g. `C:\\Python312\\python.exe` or `py -3`
- `startup-timeout` – default `30s`
- `username` – default `postgres`
- `password` – default empty
- `database` – default `postgres`
- `jdbc-params` – default `sslmode=disable&preferQueryMode=simple`
- `path-prepend` – semicolon‑separated dirs appended in front of `PATH`

## Notes

- The library provides a `@Primary` `DataSource` built on `SingleConnectionDataSource` with `suppressClose=true` and `autoCommit=false`, wrapped in `TransactionAwareDataSourceProxy`.
- Do not use connection pools; if present, keep pool size 1.
- Long transactions block all DB work; prefer short transactions in tests.
- Windows exit code 9009 means the Python command wasn’t found – set `pglite.python-exe` or ensure `py`/`python` on PATH.

[py-pglite]: https://github.com/wey-gu/py-pglite

