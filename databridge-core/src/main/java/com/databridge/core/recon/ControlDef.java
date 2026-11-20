package com.databridge.core.recon;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** A control account: a GL account in the trial balance that must equal the total of its subledgers. */
public class ControlDef {
    private String id;
    private String glAccount;
    private String name;
    private Side normal = Side.DEBIT;
    private List<SourceDef> sources = new ArrayList<>();
    private BigDecimal tolerance = BigDecimal.ZERO;
    private boolean enabled = true;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getGlAccount() { return glAccount; }
    public void setGlAccount(String glAccount) { this.glAccount = glAccount; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Side getNormal() { return normal; }
    public void setNormal(Side normal) { this.normal = normal; }
    public List<SourceDef> getSources() { return sources; }
    public void setSources(List<SourceDef> sources) { this.sources = sources == null ? new ArrayList<>() : sources; }
    public BigDecimal getTolerance() { return tolerance; }
    public void setTolerance(BigDecimal tolerance) { this.tolerance = tolerance == null ? BigDecimal.ZERO : tolerance; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
