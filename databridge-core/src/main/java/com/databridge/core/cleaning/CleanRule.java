package com.databridge.core.cleaning;

/**
 * One Cleaning Setup entry: an action on a column of a sheet. {@code sheet} or {@code field} may be
 * {@code *} for every sheet or column. Rules run in the order of the setup.
 */
public class CleanRule {
    private String id;
    private String sheet = "*";
    private String field = "*";
    private CleanAction action;
    private String pattern;
    private String replacement;
    private String value;
    private Integer length;
    private boolean enabled = true;
    private String description;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSheet() { return sheet; }
    public void setSheet(String sheet) { this.sheet = sheet; }
    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public CleanAction getAction() { return action; }
    public void setAction(CleanAction action) { this.action = action; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public String getReplacement() { return replacement; }
    public void setReplacement(String replacement) { this.replacement = replacement; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Integer getLength() { return length; }
    public void setLength(Integer length) { this.length = length; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
