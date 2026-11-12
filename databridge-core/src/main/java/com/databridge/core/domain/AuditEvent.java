package com.databridge.core.domain;

import java.time.Instant;

/** An append-only record of who did what. Details never carry client values. */
public record AuditEvent(String id, Instant occurredAt, String actor, String action,
                         String entityType, String entityId, String details) { }
