package com.databridge.core.setup;

/**
 * How long client files may live on disk.
 *
 * <p>DataBridge never stores client data in its own database. Uploaded templates
 * live only inside a workspace folder, and this mode decides when that folder
 * is destroyed.
 */
public enum RetentionMode {
    /** Destroy the workspace as soon as the processing step finishes (default). */
    PURGE_ON_COMPLETE,
    /** Keep the workspace for {@code keepHours}, then destroy it on the next sweep. */
    KEEP_FOR_HOURS
}
