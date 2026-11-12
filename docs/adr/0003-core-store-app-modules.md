# ADR 0003: A framework-free core, a JDBC store and an Undertow server

- Status: Accepted
- Date: 2026-11-05

## Context

DataBridge needs an HTTP API with a database, but its value is in the migration logic:
schema extraction, validation, reconciliation, transformation. That logic must be easy to test
without starting a server, and reusable from the command line. The original prototype ran on
plain Undertow with no framework, and that stays the approach.

## Decision

Three modules, with dependencies pointing inwards only:

| Module | Holds | Depends on |
| --- | --- | --- |
| `databridge-core` | Domain model, setups, services, repository interfaces (ports), in-memory repositories, CLI | POI, Jackson |
| `databridge-store-jdbc` | Versioned SQL scripts, schema migrator, plain-JDBC repositories | core |
| `databridge-app` | Undertow server: routes, JSON, connection pool, scheduled purge, hand wiring | core, store, Undertow, HikariCP |

- **No application framework.** The server is wired by hand in `DataBridgeServer.start`, the same
  way the original `MainServer` was. Undertow's `RoutingHandler` maps URL templates to endpoints;
  each endpoint runs on a worker thread through `BlockingHandler`.
- **No Flyway.** `SchemaMigrator` applies the scripts listed in `db/migration/index.txt` in order,
  each in a transaction, and records them in `databridge_schema_history` with a SHA-256 checksum.
  It refuses to start if an applied script was edited or the database is newer than the code.
- **Plain JDBC, no JPA.** The schema is small, the SQL stays explicit and portable between SQL
  Server and H2, and the store is tested without a server.
- One set of scripts serves SQL Server (production) and H2 in MSSQLServer mode (development and
  tests), using only constructs both accept.
- Services in core take repository interfaces, so the same code runs over in-memory repositories
  (tests, CLI) and the JDBC store (server). Contract tests in the core test-jar run against both.
- Settings are split: infrastructure (host, port, database) in `databridge-app.properties`,
  `--config` or environment variables; business settings are setups in the database.

## Consequences

- Few dependencies, fast startup, one runnable jar, and nothing hidden behind annotations.
- Most behaviour is covered by fast tests that need neither a server nor a database; the server
  itself is tested end to end over real HTTP on a random port.
- Cross-cutting features a framework would give (sign-in, request validation, metrics) are
  written explicitly, in small classes, as they are needed.
