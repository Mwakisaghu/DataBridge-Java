package com.databridge.core.domain;

import java.time.Instant;

/**
 * The sign-off of a migration run: requested by the consultant once every readiness item passes,
 * decided by Finance (never the requester). The checklist is a JSON snapshot taken at request time.
 */
public record SignOff(String runId, Status status, String certificateNo, String checklistJson, String requestedBy,
                      Instant requestedAt, String requestNote, String decidedBy, Instant decidedAt, String decisionNote) {

    public enum Status { REQUESTED, APPROVED, REJECTED }
}
