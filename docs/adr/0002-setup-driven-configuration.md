# ADR 0002: Behaviour that varies is configured through setups

- Status: Accepted
- Date: 2026-11-05

## Context

Each SACCO edits the templates: captions get renamed, columns added, sheets lack the BC type
comments, and posting groups, products and code maps differ. The legacy validators hard-coded
column positions and option lists, so every client difference meant a code change.

## Decision

DataBridge follows the Business Central pattern of setup tables. Anything that can differ by
firm, client or template is a setup:

- **DataBridge Setup**: firm-wide settings (workspace folder, limits, finding value masking).
- **Retention Setup**: how long client files live and how they are destroyed.
- **Template Setup**: per sheet, the BC table and ID, caption row, column types and required
  columns. It overrides what the template's own banner row and header comments say.

Later phases add Rule Setup, Code Mapping Setup, Control Account Setup and Integration Setup in
the same way.

Setups load in two layers: shipped defaults on the classpath, then an optional installation
folder that overrides only the values it names. Unknown keys are rejected, so a misspelt
setting fails loudly instead of being ignored. In `databridge-app` the same setups move into
database tables with pages to edit them; the JSON files remain the import/export format.

## Consequences

- A consultant can fix a client's template mapping without a release.
- Every setup value needs validation and a test.
- Setups describe structure and policy only; they never contain client data, so they may be
  committed and shared.
