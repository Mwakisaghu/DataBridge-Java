package com.databridge.core.workspace;

import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.RetentionSetup;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@link WorkspaceManager} per distinct workspace configuration. Setups can change
 * while the application runs; this hands out the manager that matches the setup in force.
 */
public final class WorkspaceManagers {

    private final Map<String, WorkspaceManager> managers = new ConcurrentHashMap<>();

    public WorkspaceManager forSetup(DataBridgeSetup setup) {
        RetentionSetup r = setup.getRetention();
        String root = setup.resolvedWorkspaceDir();
        String key = String.join("|", root, r.getMode().name(), String.valueOf(r.getKeepHours()),
                String.valueOf(r.getMaxRunHours()), String.valueOf(r.isSecureOverwrite()));
        return managers.computeIfAbsent(key, k -> new WorkspaceManager(Path.of(root), copy(r)));
    }

    private static RetentionSetup copy(RetentionSetup r) {
        RetentionSetup c = new RetentionSetup();
        c.setMode(r.getMode());
        c.setKeepHours(r.getKeepHours());
        c.setMaxRunHours(r.getMaxRunHours());
        c.setSecureOverwrite(r.isSecureOverwrite());
        c.setSweepOnStartup(r.isSweepOnStartup());
        return c;
    }
}
