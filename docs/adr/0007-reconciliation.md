# ADR 0007: Financial reconciliation (module 4)

Status: accepted, November 2026

## Context

Opening balances only migrate safely when the trial balance balances and every control account
equals its subledger (member deposits against BOSA savings, loan principal against the loan book,
receivables against customer balances...). Consultants do this by hand in Excel today. Variances
are sometimes legitimate (a write-off after cutoff) and need an explanation that Finance accepts.

## Decision

- **Reconciliation Setup**, like the other setups: shipped defaults
  (`setup/reconciliation/default.json`, fitted to the SACCO transactional template) plus stored
  overrides by id. It holds the trial-balance layout (sheet, account/debit/credit captions,
  tolerance) and control accounts: a GL account, its normal side, a tolerance, and one or more
  subledgers, each an amount column of a sheet, optionally filtered by a column that equals a value
  or ends with a suffix (savings products by account-number suffix).
- **Runs inside every submission**, after validation, on the same in-memory rows; nothing extra is
  read or kept. A sheet or column that is missing makes that line NOT_CHECKED (listed with the
  rules not checked), never a silent pass.
- **Variances are Critical findings** (`TB_BALANCE` on the last TB row; `CONTROL` on the GL
  account's row, debit or credit column by normal side), so the check fails until they are dealt
  with and they appear in the exception register.
- **Explain and approve** reuses maker-checker waivers: a consultant explains, and only the
  **FINANCE** role may approve the explanation (reviewers decide all other waivers). The variance
  amount is the finding's value, so an approved explanation carries forward to the next check only
  while the variance is exactly the same.
- **Stored per check**: the TB totals and, per control, GL balance, subledger total, variance and
  status (`recon_line`). These are account-level aggregates; the reconciliation pack (.xlsx) is
  built from them with each explanation and approver.

## Data retention

ADR 0001 says DataBridge keeps no client data and finding messages never quote client values.
Reconciliation is the one deliberate exception: it keeps and shows **account-level totals and
variances** (e.g. "GL 102110 is 81,000.00 below its subledger"), because a reconciliation cannot be
reviewed or signed off without them. No member-, loan- or customer-level figure is kept. This
choice needs the owner's confirmation; if those totals must not be kept either, the alternative is
to keep only status and the sign of each variance, which makes the pack evidence much weaker.
