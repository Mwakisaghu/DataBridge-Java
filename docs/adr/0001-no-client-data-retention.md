# ADR 0001: DataBridge does not retain client data

- Status: Accepted
- Date: 2026-11-05

## Context

Client templates hold personal data about SACCO members: names, ID numbers, KRA PINs, phone
numbers, balances and next of kin. That data is covered by the Kenya Data Protection Act 2019.
DataBridge only needs it while a migration step runs.

## Decision

1. Client files are processed only inside a **workspace**: a folder under the configured
   workspace root, created per processing step.
2. Under the default **PURGE_ON_COMPLETE** retention, the workspace is destroyed when the step
   ends. **KEEP_FOR_HOURS** keeps it for a set number of hours (default 24) for review, then
   destroys it.
3. Destroying a workspace overwrites each file with zeros before deleting it
   (`secureOverwrite`, on by default).
4. Workspaces left behind by a crash are swept on the next start once older than
   `maxRunHours` (default 6). A JVM shutdown hook also destroys open workspaces.
5. `purge --all` clears every workspace on demand.
6. DataBridge's own records (schema registry, findings, audit events) hold **structure and
   counts, not data**. A finding's offending value is masked by default (`A1********4Q`);
   the Finding Value Mode can be set to NONE, or to FULL only where the environment is cleared
   to hold client data.
7. The purge only deletes folders that carry the DataBridge marker file, so a mistaken
   workspace root cannot delete unrelated folders.
8. Logs never contain client values or file contents; purge reports carry counts only.

## Consequences

- A run cannot be re-validated later without the client re-supplying files, unless
  KEEP_FOR_HOURS is used. This is intended.
- Overwriting before deletion is reliable on hard disks; on SSDs and copy-on-write file systems
  it reduces but cannot guarantee recovery resistance. Hosts holding client data should use
  encrypted disks.
- Test suites build synthetic workbooks in memory; client templates are never committed
  (`.gitignore` blocks spreadsheets outside test resources).
