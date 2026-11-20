# ADR 0005: Validation runs on whole submissions; findings keep masked values only

- Status: Accepted
- Date: 2026-11-19

## Context

Phase 1 validates client data, not only template structure. Some checks span workbooks: a
balance in the Transactional template must belong to a loan or account in the Static template,
and Phase 2 reconciles subledgers (which need the Static product of each account) against the
trial balance. ADR 0001 forbids keeping client data once processing ends.

## Decision

1. **Submissions.** A run's workbooks are sent into one *submission*: each file is streamed into
   the submission's workspace (size-limited, SHA-256 hashed). `process` reads them all, records
   one Dataset Version per workbook, validates them together, stores the result, and closes the
   workspace (destroyed under PURGE_ON_COMPLETE). Submissions live in memory; an abandoned one is
   swept with its workspace after `maxRunHours`. A single upload is a submission of one.
2. **Two rule layers.** Schema checks come from each column's Business Central type (required,
   length, number, Integer range, date, yes/no, date formula, option values). The Rule Setup adds
   FORMAT, UNIQUE, REQUIRED, REFERENCE, SUM_EQUALS and COMPARE rules, shipped as defaults in
   `setup/rules/` and overridable per installation (same id), including switching one off.
3. **Rules that cannot run are reported, not passed.** A REFERENCE whose target sheet is not in
   the submission is listed as "not checked" on the check.
4. **Findings carry no client data.** Messages never quote values; the offending value is stored
   masked per the Finding Value Mode. The exception register (Excel) is generated from the stored
   findings, so it holds masked values only.
5. **Fail closed.** A workbook that cannot be read makes the check ERROR. Critical findings make it
   FAILED. Only a check with no Critical finding SUCCEEDS.
6. **Maker-checker waivers.** One person requests a waiver with a reason (10+ characters); a
   different person approves or rejects (a rejection needs a reason). A waiver is carried to the
   identical finding (same workbook, sheet, row, rule and masked value) in the next check.
7. **Golden test.** `samples/databridge-sample-data` (synthetic) must give exactly the answer key.

## Consequences

- Cross-workbook checks need the workbooks in one submission; the UI uploads the set together.
- Re-validating needs the files again (by design, ADR 0001).
- Whole workbooks are held in memory during processing. Large clients (100k+ members) need a
  larger heap; streaming readers are a later optimisation.
