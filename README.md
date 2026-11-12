# DataBridge

DataBridge prepares client data for Microsoft Dynamics 365 Business Central migrations. It takes the
Data Migration Templates a client fills in (static, transactional and HR), validates them, cleans
them, reconciles opening balances and produces import-ready Configuration Packages.

## Modules

| Folder | What it is | Status |
| --- | --- | --- |
| `databridge-core/` | Framework-free engine: setups, schema registry, workspaces, findings, CLI | Phase 0, in progress |
| `databridge-app/` | Spring Boot application around the core (REST API, security, database) | Phase 0, next |
| `DataBridge-Java/` | Legacy Undertow prototype and its 9 hand-coded validators | Retired in Phase 1; kept for reference |

## Principles

1. **No client data is kept.** Client files live only in a short-lived workspace that is destroyed
   when processing ends (see [ADR 0001](docs/adr/0001-no-client-data-retention.md)).
2. **Setups, not code.** Anything that differs between clients or firms is a setup
   (see [ADR 0002](docs/adr/0002-setup-driven-configuration.md)).
3. **Fail closed.** A step that crashes is reported as ERROR, never as a pass.

## Build and run

Requires JDK 17+ and Maven 3.9+.

```bash
mvn -q verify                                   # compile, run tests, build the CLI jar
java -jar databridge-core/target/databridge-core.jar --help
java -jar databridge-core/target/databridge-core.jar schema Static_Data_Templates.xlsx --out target/schema-registry.json
java -jar databridge-core/target/databridge-core.jar purge --all     # clear every client workspace now
java -jar databridge-core/target/databridge-core.jar setup           # show the setups in force
```

Add `--setup <folder>` to any command to use an installation setup folder; see
[`setup-example/`](setup-example/README.md).

## Setups

| Setup | File | Controls |
| --- | --- | --- |
| DataBridge Setup | `databridge-setup.json` | Workspace folder, retention, finding value masking, upload size limit |
| Retention Setup | `retention` block in `databridge-setup.json` | When workspaces are destroyed, secure overwrite, startup sweep |
| Template Setup | `templates/*.json` | Per sheet: BC table name and ID, caption row, column types and required flags |

Shipped defaults live in `databridge-core/src/main/resources/setup/`. An installation folder
overrides only the values it names.
