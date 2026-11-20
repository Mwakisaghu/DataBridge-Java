# DataBridge

DataBridge prepares client data for Microsoft Dynamics 365 Business Central migrations. It takes the
Data Migration Templates a client fills in (static, transactional and HR), validates them, cleans
them, reconciles opening balances and produces import-ready Configuration Packages.

## Modules

| Folder | What it is | Status |
| --- | --- | --- |
| `databridge-core/` | Framework-free engine: domain model, setups, schema registry, validation engine, workspaces, services, CLI | Phase 0-1 |
| `databridge-store-jdbc/` | Versioned SQL scripts per database, built-in migrator, database-neutral JDBC repositories | Phase 0 |
| `databridge-app/` | Undertow REST server, no framework: routes, scheduled purge, hand wiring | Phase 0 |
| `DataBridge-Java/` | Legacy Undertow prototype and its 9 hand-coded validators | Retired in Phase 1; kept for reference |

Why three modules: [ADR 0003](docs/adr/0003-core-store-app-modules.md).

## Principles

1. **No client data is kept.** Client files live only in a short-lived workspace that is destroyed
   when processing ends; the database holds structure, metadata and audit only
   ([ADR 0001](docs/adr/0001-no-client-data-retention.md)). The one exception is reconciliation:
   account-level totals and variances are kept as evidence ([ADR 0007](docs/adr/0007-reconciliation.md)).
2. **Setups, not code.** Anything that differs between clients or firms is a setup
   ([ADR 0002](docs/adr/0002-setup-driven-configuration.md)).
3. **Fail closed.** A step that crashes is reported as ERROR, never as a pass.

## Sample data

`samples/databridge-sample-data/` holds a synthetic template set (invented people and numbers):
a clean copy, a copy with planted errors and the answer key. The golden tests require DataBridge
to find exactly the planted errors: the 21 data errors, the unbalanced trial balance and both
control-account variances. See its README.

## Build

Requires JDK 17+ and Maven 3.9+. Use `verify` (not `test`): the store and app tests use the core test-jar.

```bash
mvn -q verify
```

## Command line (no database)

```bash
java -jar databridge-core/target/databridge-core-cli.jar --help
java -jar databridge-core/target/databridge-core-cli.jar schema Static_Data_Templates.xlsx --out target/schema-registry.json
java -jar databridge-core/target/databridge-core-cli.jar validate samples/databridge-sample-data/with-errors/*.xlsx --out target/register.xlsx
java -jar databridge-core/target/databridge-core-cli.jar purge --all
java -jar databridge-core/target/databridge-core-cli.jar setup --setup setup-example
```

## Day-to-day development: server plus UI

Two terminals. The UI lives in the separate `DataBridge-FrontEnd` repository; its Vite dev server
forwards `/api` and `/health` to this server, so there is no CORS setup and the server stays local.

```bash
# Terminal 1: this repository
mvn -q verify && java -jar databridge-app/target/databridge-app.jar

# Terminal 2: DataBridge-FrontEnd/data-dashboard
npm install && npm run dev        # open http://localhost:5173
```

Data is kept in H2 database files under `~/databridge-data` and survives restarts. To start afresh,
stop the server and delete that folder. Moving to PostgreSQL, MySQL or SQL Server later:
[ADR 0004](docs/adr/0004-database-portability.md).

## Server

```bash
# Development: H2 files in ~/databridge-data (kept between restarts)
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
The server listens on `http://127.0.0.1:8085` by default. The schema migrator creates or updates the
tables on every start.

### Sign-in and roles

Sign-in is on by default ([ADR 0006](docs/adr/0006-sign-in-and-roles.md)). On the first start with no
users, the server creates `admin` and prints a temporary password once in the console; sign in at
http://localhost:5173, choose your own password, then add people under **Users**.

| Role | May |
| --- | --- |
| ADMIN | Everything: users, setups, rules, clearing client files |
| CONSULTANT | Clients, projects, runs, uploads, submissions, cleaning; request waivers and explain variances |
| REVIEWER | Decide waivers (never their own: maker-checker) |
| FINANCE | Approve explanations of reconciliation variances; approve sign-off |
| VIEWER | Read everything, change nothing |

| Setting (env) | Default | |
| --- | --- | --- |
| `auth.mode` (`DATABRIDGE_AUTH`) | `local` | `none` turns sign-in off for development; refused unless the host is local |
| `auth.secureCookie` (`DATABRIDGE_SECURE_COOKIE`) | `false` | Set `true` whenever users reach DataBridge over HTTPS |
| `auth.sessionIdleMinutes` / `auth.sessionAbsoluteHours` | 120 / 12 | Session limits |
| `auth.initialAdminPassword` (`DATABRIDGE_ADMIN_PASSWORD`) | empty | First start only; empty = generate and print one |

Forgot the admin password with no other admin? Stop the server, then start it once with
`DATABRIDGE_AUTH=none`, or delete the `app_user` rows in the database to bootstrap again.

Scripts and `curl` sign in first and send the cookie back, plus the `X-DataBridge-Client` header that
every change needs (it stops other web sites posting with a signed-in browser's cookie):

```bash
curl -c jar -H "X-DataBridge-Client: cli" -H "Content-Type: application/json" \
     -d '{"username":"harry","password":"..."}' http://127.0.0.1:8085/api/auth/login
curl -b jar -X POST "http://127.0.0.1:8085/api/runs/$RUN_ID/datasets?kind=STATIC&fileName=Static.xlsx" \
     -H "X-DataBridge-Client: cli" -H "Content-Type: application/octet-stream" \
     --data-binary "@Static Data Templates.xlsx"
```

With `DATABRIDGE_AUTH=none`, name yourself in the `X-DataBridge-User` header instead.

| Method | Path | Does |
| --- | --- | --- |
| GET | `/health` | Server and database status (no sign-in needed) |
| POST | `/api/auth/login`, `/api/auth/logout` | Sign in (sets the `DB_SESSION` cookie) and out |
| GET, POST | `/api/auth/me`, `/api/auth/password` | The signed-in user; change your own password |
| GET, POST, PUT | `/api/users`, `/api/users/{id}` | User administration (ADMIN) |
| POST | `/api/users/{id}/reset-password` | Issue a new temporary password (ADMIN) |
| GET | `/api/overview` | Clients with their projects, runs and dataset counts, in one call |
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
| POST | `/api/runs/{runId}/submissions` | Open a submission: a workspace for a run's workbooks, validated together |
| POST | `/api/submissions/{id}/files?kind=STATIC&fileName=...` | Add a workbook (raw body); one per kind |
| POST | `/api/submissions/{id}/process` | Record, validate and destroy the files: 201, or 422 when a workbook could not be read |
| DELETE | `/api/submissions/{id}` | Cancel and destroy the files |
| GET | `/api/runs/{runId}/checks`, `/api/checks/{id}` | Validation checks: counts, readiness, sheet quality, rules not checked |
| GET | `/api/checks/{id}/findings?severity=&status=&sheet=&ruleType=&offset=&limit=` | Findings, masked values only |
| GET | `/api/checks/{id}/register.xlsx` | Exception register as Excel |
| POST | `/api/findings/{id}/waiver`, `/api/findings/{id}/waiver/decision` | Maker-checker waiver: request with a reason; a different user approves or rejects |
| GET, PUT, DELETE | `/api/setup/rules`, `/api/setup/rules/{id}` | Rule Setup: shipped defaults, stored overrides |
| POST | `/api/runs/{runId}/clean?kind=STATIC&fileName=...` | Clean a workbook (raw body); returns the cleaned .xlsx, job id in `X-DataBridge-Cleaning-Job` |
| GET | `/api/runs/{runId}/cleaning-jobs`, `/api/cleaning-jobs/{id}` | Cleaning history and change logs (no values) |
| GET, PUT, DELETE | `/api/setup/cleaning`, `/api/setup/cleaning/{id}` | Cleaning Setup (changes ADMIN) |
| GET | `/api/checks/{id}/reconciliation` | Trial balance and control accounts of a check, with each variance's explanation |
| GET | `/api/checks/{id}/reconciliation.xlsx` | Reconciliation pack as Excel |
| GET | `/api/setup/reconciliation` | Reconciliation Setup in force: trial balance layout, control accounts |
| PUT, DELETE | `/api/setup/reconciliation/trial-balance`, `/api/setup/reconciliation/controls/{id}` | Store or reset a part of it (ADMIN) |
| POST | `/api/retention/purge?scope=all` | Destroy every client workspace now (`scope=expired` for expired only) |
| GET | `/api/audit?entityType=&entityId=&limit=` | Audit trail, newest first |

## Setups

| Setup | Where | Controls |
| --- | --- | --- |
| DataBridge Setup | `databridge_setup` table, `/api/setup` | Workspace folder, finding value masking, upload limit, caption search depth |
| Retention Setup | same row, `retention` block | When workspaces are destroyed, secure overwrite, startup sweep |
| Template Setup | `template_setup_sheet` and `template_setup_field` tables, `/api/setup/templates` | Per sheet: BC table and ID, caption row, column types, required columns |
| Rule Setup | `rule_setup` table, `/api/setup/rules` | Formats (KRA PIN, phone, email), uniqueness, references across sheets and workbooks, group totals, comparisons |
| Cleaning Setup | `cleaning_setup` table, `/api/setup/cleaning` | Ordered cleaning rules: spaces, capitals, +254 phones, option values, padding, replacements, defaults, exact duplicate rows |
| Reconciliation Setup | `recon_setup` table, `/api/setup/reconciliation` | Trial balance layout and tolerance; control accounts (GL account, normal side, subledger sheets and filters, tolerance) |

Shipped defaults live in `databridge-core/src/main/resources/setup/`. Stored setups override them; the
CLI reads the same JSON shape from a `--setup` folder.
