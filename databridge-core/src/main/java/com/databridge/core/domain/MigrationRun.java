package com.databridge.core.domain;

import java.time.Instant;
import java.time.LocalDate;

/** One pass of a full template set through DataBridge, with its own cutoff date. */
public record MigrationRun(String id, String projectId, int runNo, RunType type, LocalDate cutoffDate,
                           RunState state, Instant createdAt, String createdBy) {

    public MigrationRun withState(RunState newState) {
        return new MigrationRun(id, projectId, runNo, type, cutoffDate, newState, createdAt, createdBy);
    }
}
