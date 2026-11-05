package com.databridge.core.workspace;

/** What a purge removed. Counts only; never file names, which may carry client names. */
public record PurgeReport(int workspaces, int files, long bytes) {
    public static PurgeReport empty() { return new PurgeReport(0, 0, 0); }

    public PurgeReport plus(PurgeReport other) {
        return new PurgeReport(workspaces + other.workspaces, files + other.files, bytes + other.bytes);
    }
}
