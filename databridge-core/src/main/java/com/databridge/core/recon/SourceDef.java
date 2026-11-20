package com.databridge.core.recon;

/**
 * One subledger feeding a control account: the amounts in {@code amountField} of {@code sheet},
 * optionally only the rows whose {@code whereField} equals {@code whereEquals} or ends with
 * {@code whereEndsWith} (e.g. savings accounts of one product, by account-number suffix).
 */
public class SourceDef {
    private String sheet;
    private String amountField;
    private String whereField;
    private String whereEquals;
    private String whereEndsWith;

    public SourceDef() { }

    public SourceDef(String sheet, String amountField, String whereField, String whereEquals, String whereEndsWith) {
        this.sheet = sheet;
        this.amountField = amountField;
        this.whereField = whereField;
        this.whereEquals = whereEquals;
        this.whereEndsWith = whereEndsWith;
    }

    public String getSheet() { return sheet; }
    public void setSheet(String sheet) { this.sheet = sheet; }
    public String getAmountField() { return amountField; }
    public void setAmountField(String amountField) { this.amountField = amountField; }
    public String getWhereField() { return whereField; }
    public void setWhereField(String whereField) { this.whereField = whereField; }
    public String getWhereEquals() { return whereEquals; }
    public void setWhereEquals(String whereEquals) { this.whereEquals = whereEquals; }
    public String getWhereEndsWith() { return whereEndsWith; }
    public void setWhereEndsWith(String whereEndsWith) { this.whereEndsWith = whereEndsWith; }

    /** How a person would describe this source, e.g. "BOSA SAVINGS AC, Account No. ending 01". */
    public String describe() {
        String s = sheet + " / " + amountField;
        if (whereField != null && whereEquals != null) s += " where " + whereField + " = " + whereEquals;
        if (whereField != null && whereEndsWith != null) s += " where " + whereField + " ends " + whereEndsWith;
        return s;
    }
}
