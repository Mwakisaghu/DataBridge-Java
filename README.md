# DataBridge

DataBridge prepares client data for Microsoft Dynamics 365 Business Central migrations. It takes the
Data Migration Templates a client fills in (static, transactional and HR), validates them, cleans
them, reconciles opening balances and produces import-ready Configuration Packages.

## Modules

| Folder | What it is | Status |
| --- | --- | --- |
| `databridge-core/` | Framework-free engine: domain model, setups, schema registry, workspaces, services, CLI | Phase 0 |
| `databridge-store-jdbc/` | Database schema (versioned SQL scripts, built-in migrator) and plain-JDBC repositories | Phase 0 |
| `databridge-app/` | Undertow REST server, no framework: routes, scheduled purge, hand wiring | Phase 0 |
| `DataBridge-Java/` | Legacy Undertow prototype and its 9 hand-coded validators | Retired in Phase 1; kept for reference |

Why three modules: [ADR 0003](docs/adr/0003-core-store-app-modules.md).

## Principles

1. **No client data is kept.** Client files live only in a short-lived workspace that is destroyed
   when processing ends; the database holds structure, metadata and audit only
   ([ADR 0001](docs/adr/0001-no-client-data-retention.md)).
2. **Setups, not code.** Anything that differs between clients or firms is a setup
   ([ADR 0002](docs/adr/0002-setup-driven-configuration.md)).
3. **Fail closed.** A step that crashes is reported as ERROR, never as a pass.

## Build

Requires JDK 17+ and Maven 3.9+. Use `verify` (not `test`): the store and app tests use the core test-jar.

```bash
mvn -q verify
```

## Command line (no database)

```bash
java -jar databridge-core/target/databridge-core-cli.jar --help
java -jar databridge-core/target/databridge-core-cli.jar schema Static_Data_Templates.xlsx --out target/schema-registry.json
java -jar databridge-core/target/databridge-core-cli.jar purge --all
java -jar databridge-core/target/databridge-core-cli.jar setup --setup setup-example
```

## Server

```bash
# Development: in-memory H2 in SQL Server mode (data is lost on restart)
java -jar databridge-app/target/databridge-app.jar

# SQL Server: environment variables, or put the same keys in a file and pass --config <file>
export DATABRIDGE_DB_URL="jdbc:sqlserver://localhost:1433;databaseName=DataBridge;encrypt=true;trustServerCertificate=true"
export DATABRIDGE_DB_USER=databridge
export DATABRIDGE_DB_PASSWORD='...'
java -jar databridge-app/target/databridge-app.jar
```

Infrastructure settings and their defaults are in
[`databridge-app.properties`](databridge-app/src/main/resources/databridge-app.properties)
(`server.host`, `server.port`, `db.url`, `db.user`, `db.password`, `retention.sweepIntervalMinutes`).
The server listens on `http://127.0.0.1:8085` only, until sign-in arrives (P0-03). The schema migrator
creates or updates the tables on every start. Name yourself in the `X-DataBridge-User` header; it goes
into the audit trail.

Upload a workbook as the raw request body, which is streamed straight into a workspace:

```bash
curl -X POST "http://127.0.0.1:8085/api/runs/$RUN_ID/datasets?kind=STATIC&fileName=Static.xlsx" \
     -H "X-DataBridge-User: harry" -H "Content-Type: application/octet-stream" \
     --data-binary "@Static Data Templates.xlsx"
```

| Method | Path | Does |
| --- | --- | --- |
| GET | `/health` | Server and database status |
| GET, PATCH | `/api/setup` | DataBridge Setup and Retention Setup (PATCH changes only the values sent) |
| GET | `/api/setup/templates` | Template Setups in force (shipped defaults plus stored) |
| PUT, DELETE | `/api/setup/templates/{sheetName}` | Store or remove a Template Setup |
| GET, POST | `/api/setup/export`, `/api/setup/import` | Move stored setups between environments |
| GET, POST | `/api/clients` | Clients |
| GET, POST | `/api/clients/{clientId}/projects` | Projects |
| GET, POST | `/api/projects/{projectId}/runs` | Migration runs (MOCK or CUTOVER, with cutoff date) |
| GET, POST | `/api/runs/{runId}`, `/api/runs/{runId}/close` | A run; close it to stop uploads |
| POST | `/api/runs/{runId}/datasets?kind=STATIC&fileName=...` | Upload a workbook (raw body): 201, or 422 if it could not be read |
| GET | `/api/runs/{runId}/datasets`, `/api/datasets/{id}`, `/api/datasets/{id}/schema` | Dataset versions and their schema |
| POST | `/api/retention/purge?scope=all` | Destroy every client workspace now (`scope=expired` for expired only) |
| GET | `/api/audit?entityType=&entityId=&limit=` | Audit trail, newest first |

## Setups

| Setup | Where | Controls |
| --- | --- | --- |
| DataBridge Setup | `databridge_setup` table, `/api/setup` | Workspace folder, finding value masking, upload limit, caption search depth |
| Retention Setup | same row, `retention` block | When workspaces are destroyed, secure overwrite, startup sweep |
| Template Setup | `template_setup_sheet` and `template_setup_field` tables, `/api/setup/templates` | Per sheet: BC table and ID, caption row, column types, required columns |

Shipped defaults live in `databridge-core/src/main/resources/setup/`. Stored setups override them; the
CLI reads the same JSON shape from a `--setup` folder.
