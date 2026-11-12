# Example installation setup

Copy this folder, edit it, and pass it with `--setup <folder>`. Only the values you write
override the shipped defaults; everything else keeps its default.

- `databridge-setup.json`: DataBridge Setup and Retention Setup.
- `templates/*.json`: Template Setups, one array of sheets per file. Files load in name order,
  and a later definition of the same sheet replaces an earlier one.

## Template Setup fields

| Field | Meaning |
| --- | --- |
| `sheetName` | Sheet to match (case and spacing are ignored) |
| `tableName`, `tableId` | Target Business Central table, overriding the template's banner row |
| `captionRow` | 1-based caption row, when automatic detection should be skipped |
| `purpose` | What the sheet feeds in BC, e.g. "Opening balance journal" |
| `fields[]` | `caption`, `type` (Code20, Text100, Decimal, Integer, Boolean, Date, DateFormula, Option), `options` (for Option), `required` |

Run `databridge setup --setup <folder>` to see the merged result.
