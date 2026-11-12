package com.databridge.core.service;

import com.databridge.core.domain.AuditEvent;
import com.databridge.core.port.AuditRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Writes audit events. Callers must pass details that hold no client values. */
public final class AuditLog {

    public static final int MAX_DETAILS = 2000;

    private final AuditRepository repo;
    private final Clock clock;

    public AuditLog(AuditRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public AuditEvent record(String actor, String action, String entityType, String entityId, String details) {
        String d = details == null || details.length() <= MAX_DETAILS ? details : details.substring(0, MAX_DETAILS);
        AuditEvent e = new AuditEvent(UUID.randomUUID().toString(), now(), actor, action, entityType, entityId, d);
        repo.append(e);
        return e;
    }

    public List<AuditEvent> list(String entityType, String entityId, int limit) {
        return repo.list(entityType, entityId, Math.max(1, Math.min(limit, 1000)));
    }

    Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /** Checks an actor name: required, at most 100 characters. */
    public static String actor(String actor) {
        if (actor == null || actor.isBlank()) throw new ValidationException("An actor (user name) is required");
        String a = actor.trim();
        if (a.length() > 100) throw new ValidationException("Actor name is longer than 100 characters");
        return a;
    }
}
