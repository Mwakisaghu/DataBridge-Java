package com.databridge.core.domain;

import com.databridge.core.finding.FindingStatus;
import com.databridge.core.finding.Severity;

/** How many findings of a check have this severity and status. */
public record FindingCount(Severity severity, FindingStatus status, int count) { }
