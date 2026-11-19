package com.databridge.core.validation;

import com.databridge.core.finding.Severity;

import java.math.BigDecimal;

/**
 * One rule in the Rule Setup. Shipped defaults live in {@code /setup/rules/}; stored rules with the
 * same id override them, and a stored rule can switch a default off with {@code enabled: false}.
 */
public class RuleDef {

    private String id;
    private RuleType type;
    private String sheet;
    private String field;
    private Severity severity = Severity.CRITICAL;
    private boolean enabled = true;
    private boolean autoFixable;
    private String description;
    /** FORMAT: a named pattern or a regular expression. */
    private String pattern;
    /** REFERENCE: the sheet and field that must hold the value. */
    private String targetSheet;
    private String targetField;
    /** SUM_EQUALS: group rows by this field; optionally only rows where whereField equals whereEquals. */
    private String groupBy;
    private String whereField;
    private String whereEquals;
    private BigDecimal expected;
    /** COMPARE: field OPERATOR otherField, OPERATOR one of LT, LE, EQ, GE, GT. */
    private String operator;
    private String otherField;

    public String getId() { return id; }
    public RuleType getType() { return type; }
    public String getSheet() { return sheet; }
    public String getField() { return field; }
    public Severity getSeverity() { return severity; }
    public boolean isEnabled() { return enabled; }
    public boolean isAutoFixable() { return autoFixable; }
    public String getDescription() { return description; }
    public String getPattern() { return pattern; }
    public String getTargetSheet() { return targetSheet; }
    public String getTargetField() { return targetField; }
    public String getGroupBy() { return groupBy; }
    public String getWhereField() { return whereField; }
    public String getWhereEquals() { return whereEquals; }
    public BigDecimal getExpected() { return expected; }
    public String getOperator() { return operator; }
    public String getOtherField() { return otherField; }

    public void setId(String id) { this.id = id; }
    public void setType(RuleType type) { this.type = type; }
    public void setSheet(String sheet) { this.sheet = sheet; }
    public void setField(String field) { this.field = field; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setAutoFixable(boolean autoFixable) { this.autoFixable = autoFixable; }
    public void setDescription(String description) { this.description = description; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public void setTargetSheet(String targetSheet) { this.targetSheet = targetSheet; }
    public void setTargetField(String targetField) { this.targetField = targetField; }
    public void setGroupBy(String groupBy) { this.groupBy = groupBy; }
    public void setWhereField(String whereField) { this.whereField = whereField; }
    public void setWhereEquals(String whereEquals) { this.whereEquals = whereEquals; }
    public void setExpected(BigDecimal expected) { this.expected = expected; }
    public void setOperator(String operator) { this.operator = operator; }
    public void setOtherField(String otherField) { this.otherField = otherField; }
}
