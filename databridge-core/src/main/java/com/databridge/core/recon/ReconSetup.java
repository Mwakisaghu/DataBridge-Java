package com.databridge.core.recon;

import java.util.ArrayList;
import java.util.List;

/** The Reconciliation Setup: the trial balance layout and the control accounts. */
public class ReconSetup {
    private TrialBalanceDef trialBalance = new TrialBalanceDef();
    private List<ControlDef> controls = new ArrayList<>();

    public TrialBalanceDef getTrialBalance() { return trialBalance; }
    public void setTrialBalance(TrialBalanceDef trialBalance) { this.trialBalance = trialBalance == null ? new TrialBalanceDef() : trialBalance; }
    public List<ControlDef> getControls() { return controls; }
    public void setControls(List<ControlDef> controls) { this.controls = controls == null ? new ArrayList<>() : controls; }
}
