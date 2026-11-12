package com.databridge.core.service;

import com.databridge.core.workspace.PurgeReport;
import com.databridge.core.workspace.WorkspaceManager;
import com.databridge.core.workspace.WorkspaceManagers;

/** Sweeps expired workspaces and clears all client files on demand, with an audit trail. */
public final class RetentionService {

    public static final String SYSTEM = "system";

    private final SetupService setups;
    private final WorkspaceManagers workspaces;
    private final AuditLog audit;

    public RetentionService(SetupService setups, WorkspaceManagers workspaces, AuditLog audit) {
        this.setups = setups;
        this.workspaces = workspaces;
        this.audit = audit;
    }

    /** Destroys expired workspaces. Audited only when something was removed. */
    public PurgeReport sweep() {
        PurgeReport r = manager().purgeExpired();
        if (r.workspaces() > 0) {
            audit.record(SYSTEM, "WORKSPACES_PURGED", "WORKSPACE", null, "Expired: " + counts(r));
        }
        return r;
    }

    /** Destroys every workspace now. Always audited. */
    public PurgeReport purgeAll(String actor) {
        String who = AuditLog.actor(actor);
        PurgeReport r = manager().purgeAll();
        audit.record(who, "WORKSPACES_PURGED", "WORKSPACE", null, "All: " + counts(r));
        return r;
    }

    public boolean sweepOnStartup() {
        return setups.effective().general().getRetention().isSweepOnStartup();
    }

    private WorkspaceManager manager() {
        return workspaces.forSetup(setups.effective().general());
    }

    private static String counts(PurgeReport r) {
        return r.workspaces() + " workspace(s), " + r.files() + " file(s), " + r.bytes() + " byte(s)";
    }
}
