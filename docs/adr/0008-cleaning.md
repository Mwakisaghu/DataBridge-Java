# ADR 0008: Cleaning and transformation (module 3)

Status: accepted, November 2026

## Context

Many findings are mechanical: spaces, capitals, phone numbers without +254, option values in the
wrong case or with an obvious misspelling, rows pasted twice. Consultants fix these by hand in each
mock run. DataBridge must help without becoming a store of client data, and must never "fix"
something a person should decide.

## Decision

- **Cleaning Setup**: ordered rules (shipped defaults in `setup/cleaning/default.json`, stored
  overrides by id). Actions: TRIM, UPPER, LOWER, PHONE_KE, OPTION_MATCH, PAD_LEFT, REPLACE,
  DEFAULT_VALUE, REMOVE_DUPLICATE_ROWS. `sheet` and `field` may be `*`.
- **Only certain changes.** OPTION_MATCH fixes a value only when it differs from one option in case
  or spaces, or when exactly one option is within two edits (the validator now marks such a finding
  auto-fixable only in that case). PHONE_KE changes only numbers it can read as Kenyan mobiles.
  REMOVE_DUPLICATE_ROWS empties only rows identical in every column (emptied, not deleted, so Excel
  table ranges and row numbers stay valid); a duplicated key with different data stays a finding.
  Formula cells are never touched; every cell keeps its style.
- **Clean and download, keep nothing.** The workbook is streamed into a short-lived workspace,
  cleaned in memory, returned in the response and the workspace destroyed. Kept: a cleaning job
  (file name, SHA-256 before and after, counts, who, when) and a change log of rule, sheet, column,
  number of cells and the first row numbers. Never a value, before or after.
- The cleaned file is then submitted through Intake as usual, so the next check proves the effect.
- Golden test: cleaning the sample error set and resubmitting removes exactly the five planted
  errors the answer key marks auto-fixable, and creates no new finding.

## Consequences

- POI writes a new value next to an existing inline string instead of replacing it; the cleaner
  resets each cell before writing (found by the golden test on the sample files).
- Cleaning needs the CONSULTANT role; the Cleaning Setup is ADMIN-only.
