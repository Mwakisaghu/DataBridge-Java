package com.databridge.core.validation;

/**
 * Data quality of one sheet in a check. A row passes when it has no Critical finding.
 * {@code passRate} is passing rows over all rows, 1.0 for an empty sheet.
 */
public record SheetStat(String kind, String sheet, Integer tableId, int rows, int rowsWithCritical,
                        int rowsWithWarning, int critical, int warning, int info) {

    public double passRate() {
        return rows == 0 ? 1.0 : (double) (rows - rowsWithCritical) / rows;
    }
}
