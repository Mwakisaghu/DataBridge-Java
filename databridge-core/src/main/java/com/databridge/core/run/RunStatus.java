package com.databridge.core.run;

/** Result of one processing step. */
public enum RunStatus {
    /** The step ran to the end and found nothing blocking. */
    SUCCEEDED,
    /** The step ran to the end and found blocking problems in the data. */
    FAILED,
    /** The step could not run to the end. Never treated as a pass. */
    ERROR
}
