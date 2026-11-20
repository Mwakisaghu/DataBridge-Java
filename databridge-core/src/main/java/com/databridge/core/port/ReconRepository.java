package com.databridge.core.port;

import com.databridge.core.recon.ControlDef;
import com.databridge.core.recon.ReconLine;
import com.databridge.core.recon.TrialBalanceDef;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Stored Reconciliation Setup entries and each check's reconciliation lines. */
public interface ReconRepository {
    /** The id under which a stored trial balance layout is kept. */
    String TRIAL_BALANCE_ID = "trial-balance";

    List<ControlDef> controls();
    void saveControl(ControlDef c, String actor, Instant at);
    boolean deleteControl(String id);

    Optional<TrialBalanceDef> trialBalance();
    void saveTrialBalance(TrialBalanceDef tb, String actor, Instant at);
    boolean deleteTrialBalance();

    void insertLines(String checkId, String runId, List<ReconLine> lines);
    List<ReconLine> lines(String checkId);
}
