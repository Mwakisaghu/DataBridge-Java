package com.databridge.core.port;

import com.databridge.core.domain.AuditEvent;

import java.util.List;

public interface AuditRepository {
    void append(AuditEvent event);
    /** Newest first. entityType and entityId may be null to match any. */
    List<AuditEvent> list(String entityType, String entityId, int limit);
}
