package com.databridge.core.finding;

/**
 * One check result. {@code value} is already masked according to the
 * Finding Value Mode, so a finding can be stored and reported after the
 * client file itself has been purged.
 */
public record Finding(
        Severity severity,
        String sheet,
        int row,
        String column,
        String field,
        String ruleId,
        String message,
        String value,
        FindingStatus status) {

    public static Finding open(Severity severity, String sheet, int row, String column, String field,
                               String ruleId, String message, String maskedValue) {
        return new Finding(severity, sheet, row, column, field, ruleId, message, maskedValue, FindingStatus.OPEN);
    }
}
