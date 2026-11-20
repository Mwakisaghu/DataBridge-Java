# DataBridge sample data

Synthetic test data for DataBridge data runs, written into copies of the real Data Migration
Templates: same sheets, captions, header type comments and Excel tables. Every person, phone
number, ID and KRA PIN is invented; emails use example.com. Cutoff date: 31 Oct 2026.

## Folders

- `clean/` - three workbooks with no planted errors. All values fit their Business Central types and
  option lists, every cross-reference resolves, the trial balance balances and every control
  account equals its subledger.
- `with-errors/` - the same data with 23 errors planted on purpose (24 expected findings: one
  error also causes a control-account variance).
- `DataBridge sample data - answer key.xlsx` - each planted error with its exact cell, plus row
  counts and the control totals of the clean set.
- `seeded-errors.json` - the same answer key, for automated tests.

## What is in it

| Area | Records |
| --- | --- |
| Members | 150 (24 are staff), with 294 nominees |
| Savings accounts | BOSA deposits, share capital and BBF for every member; FOSA main accounts for most |
| Loans | 85 (development, emergency, salary advance, FOSA business), 149 guarantors, 6 securities |
| Other static | Employers, vendors, banks, fixed assets, items, standing orders, fixed deposits, liens, recoveries, salary lines, dividends, delegates, signatories, file movements |
| HR | 24 employees linked to their member records, salary cards, leave, earnings and deductions |
| Opening balances | Trial balance and every subledger at cutoff |

## Using it

1. Upload the three `clean/` files to a run. Expect each to be read with the same structural
   warnings as the original templates (sheets without a BC table ID, untyped columns).
2. Upload the `with-errors/` files as the next version. Intake passes them too: it checks structure.
   The planted errors are data errors and reconciliation breaks, which validation and
   reconciliation must find exactly as listed in the answer key.

## A real template flaw the clean set shows

DataBridge reports 48 Critical findings on the clean set, all on `Employee Upload`, columns
`Home Phone Number` and `Cellular Phone Number`. The HR template types them `Integer`, and a
12-digit phone number exceeds Business Central's Integer range (2,147,483,647). This is in the
original template, not the sample data: those columns should be `Text30` or `BigInteger`. The
golden tests expect exactly these 48 on the clean set, and exactly the 48 plus the planted errors
on the error set.
