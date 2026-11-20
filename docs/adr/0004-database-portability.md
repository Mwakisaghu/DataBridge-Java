# ADR 0004: Database-neutral store; H2 files for development

- Status: Accepted
- Date: 2026-11-12

## Context

The production database is not decided: PostgreSQL or MySQL are likely, SQL Server is possible.
Development needs a database that survives restarts and needs no installation, so features can
be tested through the UI as they are built.

## Decision

1. **Queries are database-neutral.** Repositories use ANSI SQL and JDBC only: row limits through
   `Statement.setMaxRows`, timestamps through `java.sql.Timestamp`, dates through `LocalDate`, ids
   as strings generated in Java, no vendor functions. The same repository code runs on every
   supported database.
2. **Only the DDL differs.** `SchemaMigrator` picks scripts by the connected database:
   `db/migration/<database>/index.txt` when that folder exists (`postgresql`, `mysql`, ...),
   otherwise the shared default scripts in `db/migration/`, which serve SQL Server and H2 in
   MSSQLServer mode. A database with no scripts fails at startup with a message naming the folder
   to add.
3. **Development default: H2 database files** under `~/databridge-data`, in MSSQLServer mode.
   Nothing to install; data survives restarts; delete the folder to start afresh. Tests use H2 in
   memory.

## Adding PostgreSQL or MySQL

1. Add the JDBC driver to `databridge-app/pom.xml` (runtime scope).
2. Translate `V1__baseline.sql` into `db/migration/postgresql/V1__baseline.sql` (or `mysql/`):
   `NVARCHAR(n)` becomes `VARCHAR(n)`, `NVARCHAR(MAX)` becomes `TEXT` (MySQL: `LONGTEXT`),
   `DATETIME2` becomes `TIMESTAMP` (MySQL: `DATETIME(3)`), `BIT` becomes `BOOLEAN`. List it in that
   folder's `index.txt`. Later versions get a script per folder.
3. Run the store's test suite against the new database (point `TestDatabase` at it, or use H2's
   matching mode: `MODE=PostgreSQL` or `MODE=MySQL`).
4. Set `db.url`, `db.user` and `db.password`.

## Consequences

- Choosing the production database later costs one set of DDL scripts and a test run, not a
  rewrite of the store.
- Every new migration needs one script per supported database; until a second database is
  adopted there is only the default set.
- H2 development data lives on the developer's machine. It holds setups, clients, runs, dataset
  metadata and the audit trail, never client data (ADR 0001).
