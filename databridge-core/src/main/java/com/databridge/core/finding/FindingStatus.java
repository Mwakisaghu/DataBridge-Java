package com.databridge.core.finding;

/**
 * Where a finding is in its life. A waiver is maker-checker: one person requests it with a
 * reason, a different person approves (WAIVED) or rejects it (back to OPEN).
 */
public enum FindingStatus { OPEN, WAIVER_REQUESTED, WAIVED, RESOLVED }
