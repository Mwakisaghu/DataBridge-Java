# ADR 0010: Reporting, sign-off and certificate (module 6)

Status: accepted, November 2026

## Context

A migration run ends with someone accountable saying "this data may go live". That decision needs
the state of the run in one place and evidence that cannot be argued with later.

## Decision

- **Run summary** (`GET /api/runs/{runId}/summary`): readiness trend over checks, Critical findings
  open / awaiting / waived, reconciliation (tie, variance, not checked, unapproved), cleaning,
  packages and import status, and a **readiness checklist** computed from the data:
  1. Workbooks validated: the latest check ran to the end with no Critical finding open or awaiting
     a waiver decision.
  2. Opening balances reconciled: every line checked, every variance explained and approved by
     Finance.
  3. Import package built from the latest check.
  4. Every packaged table imported or matched against Business Central.
- **Maker-checker sign-off.** A consultant can request sign-off only when every item passes; the
  checklist is snapshotted with the request. Only FINANCE may decide, never the requester, and the
  checklist is evaluated again at approval (fail closed if anything changed). A rejection needs a
  reason and allows a new request.
- **Approval** issues a certificate number `DB-<client>-<project>-R<run>-<yyyyMMdd>` and closes the
  run. The UI prints the certificate (browser "save as PDF"); the sign-off pack (.xlsx) holds the
  sign-off, checklist, KPIs, trend and the run's events. Counts and decisions only.
- The audit trail can be exported (`GET /api/audit.xlsx`, up to 1,000 entries per export).
