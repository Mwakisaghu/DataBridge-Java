package com.databridge.core.recon;

import java.math.BigDecimal;

/** Where the trial balance is and how its columns are captioned; how far debits and credits may differ. */
public class TrialBalanceDef {
    private String sheet = "TRIAL BALANCE";
    private String accountField = "No.";
    private String nameField = "Description";
    private String debitField = "Debit";
    private String creditField = "Credit";
    private BigDecimal tolerance = BigDecimal.ZERO;

    public String getSheet() { return sheet; }
    public void setSheet(String sheet) { this.sheet = sheet; }
    public String getAccountField() { return accountField; }
    public void setAccountField(String accountField) { this.accountField = accountField; }
    public String getNameField() { return nameField; }
    public void setNameField(String nameField) { this.nameField = nameField; }
    public String getDebitField() { return debitField; }
    public void setDebitField(String debitField) { this.debitField = debitField; }
    public String getCreditField() { return creditField; }
    public void setCreditField(String creditField) { this.creditField = creditField; }
    public BigDecimal getTolerance() { return tolerance; }
    public void setTolerance(BigDecimal tolerance) { this.tolerance = tolerance == null ? BigDecimal.ZERO : tolerance; }
}
