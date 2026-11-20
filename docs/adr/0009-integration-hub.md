# ADR 0009: Migration and integration hub (module 5)

Status: accepted, November 2026

## Context

After validation, cleaning and reconciliation, the data goes into Business Central through
configuration packages. Mistakes here are expensive: importing a file other than the one that was
checked, importing tables in the wrong order (lines before headers, loans before members), or not
noticing that only part of a table arrived.

## Decision

- **Import packages from the validated file only.** `POST /api/runs/{runId}/package` accepts a
  workbook only if its SHA-256 equals a dataset of the run's latest check and that workbook has no
  Critical finding that is still open or awaiting a waiver decision (run-level findings block every
  package). `requireCleanCheck` in the Integration Setup can switch this off; the package then says
  so on its first sheet and the job records that no check was used.
- **Business Central's own layout.** The templates already carry the configuration-package header
  (package code, table name, table ID in row 1), so the package is the same workbook reduced to the
  sheets that name a table, reordered for import, with an "Import order" sheet first. Opening
  balances (trial balance, subledger balances) have no table ID: they are posted through journals,
  not packages.
- **Import order from the Rule Setup.** A sheet comes after every sheet its REFERENCE rules point
  to; cycles are broken in template order and reported. Adding a reference rule both checks the data
  and fixes the order (Standing Order Lines → Header, Loan Recovery Lines → Header and Members →
  employer were added for this).
- **Import tracking** per run and sheet: rows packaged, rows the consultant saw imported
  (IMPORTED / PARTIAL / FAILED), and the count read back from Business Central (MATCHED / MISMATCH).
- **Read-only Business Central client.** OAuth2 client credentials against Microsoft Entra ID, then
  API v2.0 (or a custom API path per sheet) `$count` queries. DataBridge never writes to Business
  Central. The client secret is never stored: the setup names the server environment variable that
  holds it. URLs must be https (plain http only to localhost, for testing). A sign-in or connection
  failure stops a verification; a table that cannot be counted is reported on that table only.
- The package file is returned and never kept: a job records fingerprints and the table list.

## Needs the owner's input

- Tenant ID, environment, company ID and an Entra app registration (client ID and secret) for a
  sandbox, with the app added in Business Central (Microsoft Entra Applications) with read access.
- API pages for the SACCO tables (Members, Loans, Savings Accounts...). Standard entities are mapped
  by default; custom tables need AL API pages, then an entity map such as
  `{"sheet":"Members","apiPath":"api/skyworld/sacco/v1.0","entitySet":"members"}`.
- Confirmation, on a real tenant, that Business Central's "Import from Excel" accepts the package
  layout as produced (it keeps the template sheets unchanged apart from order and removed sheets).
