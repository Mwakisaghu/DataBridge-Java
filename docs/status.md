# DataBridge status

November 2026. All six modules are implemented, tested and documented in both repositories.

## What is built

| Area | State | Evidence |
| --- | --- | --- |
| 1 Migration Control Centre | Done | Clients, projects, mock/cutover runs, overview |
| 2 Template & Data Validation | Done | 50 shipped rules; golden test finds exactly the 24 planted errors |
| 3 Cleaning & Transformation | Done | Golden test: cleaning fixes exactly the 5 auto-fixable planted errors, adds none |
| 4 Financial Reconciliation | Done | TB and 9 SACCO control accounts; 81,000.00 / 50,000.00 / 1,000.00 planted breaks found |
| 5 Migration & Integration Hub | Done | Packages gated on the validated file; BC client tested against a local fake BC |
| 6 Reporting & Audit Centre | Done | Lifecycle test: first check to signed-off certificate and closed run |
| Sign-in and roles | Done | Local accounts, sessions, 5 roles, per-route access, cross-site guard |
| Production | Done | UI served by the server, security headers (no CSP violations on any page), Docker, compose with HTTPS, CI |

Tests: 167 server tests (compiled with `--release 17 -Werror`), 42 UI tests, ESLint with zero
warnings, production build. Browser end-to-end runs of every module against a real server.

## Bugs found and fixed along the way

- Temporary passwords failed the password policy about 1 time in 12 (first-admin bootstrap, new
  users and resets could fail).
- Users page ignored the ticked roles: every new user became a Consultant.
- Cleaning: cells stored as inline strings kept their old text after a change.
- Packages: a new sheet inherited links to deleted comment parts (Excel would "repair" the file).
- Reconciliation tile said "Done" when nothing had been reconciled.

## Needs your input or access

1. **Push to GitHub.** The Claude GitHub App is not installed on the `Mwakisaghu` repositories, so
   these commits could not be pushed from here. Install it at
   https://github.com/apps/claude/installations/select_target (or reconnect GitHub in claude.ai
   settings), or apply the git bundles provided with this delivery and push yourself.
2. **Run `mvn -B verify` once** (or let CI run it). Maven Central is blocked in the build
   workspace, so the server was compiled and tested with `javac` against slightly older builds of
   the same libraries (POI 5.2.2 vs 5.2.5, Jackson 2.14 vs 2.17). Docker and compose were written
   but not run here for the same reason.
3. **Business Central sandbox**: tenant ID, environment, company ID, and an Entra app (client ID
   and secret) added in Business Central with read access. Then test the connection in
   **Setup > Business Central**.
4. **API pages for SACCO tables** (Members, Loans, Savings Accounts...) so their counts can be read
   back; standard entities (customers, vendors, items, G/L accounts, bank accounts, fixed assets,
   employees) are mapped already.
5. **Confirm on a real tenant** that configuration-package "Import from Excel" accepts the package
   layout as produced (the template sheets unchanged apart from order and removed sheets).
6. **Reconciliation keeps account totals** (e.g. "GL 102110 is 81,000.00 below its subledger") as
   evidence. This is the one deliberate exception to "no client data" (ADR 0007). Confirm, or ask
   for status-only reconciliation.
7. **HR template flaw**: `Employee Upload` types Home and Cellular Phone Number as Integer; 12-digit
   numbers overflow Business Central's Integer. Change those columns to Text30 in the template (or
   waive the findings, as the lifecycle test does).
8. **Microsoft Entra ID sign-in** for DataBridge users (instead of local accounts) needs the tenant
   and an app registration; the session layer is ready for it (ADR 0006).
9. **PostgreSQL / MySQL**: per-database script folders are designed (ADR 0004) but not yet written;
   H2 and SQL Server work today.

## Next steps worth considering

- Opening-balance journals: generate General Journal lines from the transactional sheets (they
  have no configuration-package table today).
- PostgreSQL scripts and a CI job that runs the store tests against PostgreSQL.
- Entra ID single sign-on.
