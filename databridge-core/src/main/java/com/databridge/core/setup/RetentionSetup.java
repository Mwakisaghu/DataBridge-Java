package com.databridge.core.setup;

/** Retention Setup: when and how client files are destroyed. */
public class RetentionSetup {

    private RetentionMode mode = RetentionMode.PURGE_ON_COMPLETE;
    /** Used when mode is KEEP_FOR_HOURS. */
    private int keepHours = 24;
    /**
     * Safety net for PURGE_ON_COMPLETE: a workspace left behind by a crash is
     * swept once it is older than this.
     */
    private int maxRunHours = 6;
    /** Overwrite file contents with zeros before deleting. */
    private boolean secureOverwrite = true;
    /** Sweep expired workspaces every time the engine starts. */
    private boolean sweepOnStartup = true;

    public RetentionMode getMode() { return mode; }
    public int getKeepHours() { return keepHours; }
    public int getMaxRunHours() { return maxRunHours; }
    public boolean isSecureOverwrite() { return secureOverwrite; }
    public boolean isSweepOnStartup() { return sweepOnStartup; }

    public void setMode(RetentionMode mode) { this.mode = mode; }
    public void setKeepHours(int keepHours) { this.keepHours = keepHours; }
    public void setMaxRunHours(int maxRunHours) { this.maxRunHours = maxRunHours; }
    public void setSecureOverwrite(boolean secureOverwrite) { this.secureOverwrite = secureOverwrite; }
    public void setSweepOnStartup(boolean sweepOnStartup) { this.sweepOnStartup = sweepOnStartup; }

    void validate() {
        if (mode == null) throw new SetupException("retention.mode is required");
        if (keepHours < 1 || keepHours > 720) throw new SetupException("retention.keepHours must be 1-720");
        if (maxRunHours < 1 || maxRunHours > 72) throw new SetupException("retention.maxRunHours must be 1-72");
    }
}
