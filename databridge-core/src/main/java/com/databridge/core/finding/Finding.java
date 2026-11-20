package com.databridge.core.finding;

/**
 * One check result. {@code value} is already masked according to the Finding Value Mode and
 * {@code message} never quotes client values, so a finding can be stored and reported after the
 * client file itself has been purged. Reconciliation findings (TB_BALANCE, CONTROL) are the one
 * exception: they state account-level totals and variances (ADR 0007).
 *
 * @param kind       template workbook the sheet came from (STATIC, TRANSACTIONAL, HR, OTHER), or null for run-level checks
 * @param ruleId     the rule that produced it, e.g. {@code schema.option} or {@code members.kra-pin}
 * @param ruleType   what kind of problem: REQUIRED, LENGTH, NUMBER, DATE, BOOLEAN, DATEFORMULA, OPTION,
 *                   FORMAT, DUPLICATE, REFERENCE, RULE, TB_BALANCE or CONTROL
 * @param autoFixable whether a cleaning recipe can correct it without a person deciding
 */
public record Finding(
        Severity severity,
        String kind,
        String sheet,
        int row,
        String column,
        String field,
        String ruleId,
        String ruleType,
        String message,
        String value,
        boolean autoFixable,
        FindingStatus status) {

    public static Finding open(Severity severity, String kind, String sheet, int row, String column, String field,
                               String ruleId, String ruleType, String message, String maskedValue, boolean autoFixable) {
        return new Finding(severity, kind, sheet, row, column, field, ruleId, ruleType, message, maskedValue, autoFixable,
                FindingStatus.OPEN);
    }
}
