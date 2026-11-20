package com.databridge.core.security;

import java.time.Instant;
import java.util.Set;

/** A DataBridge user. {@code passwordHash} never leaves the server. */
public record User(String id, String username, String displayName, String passwordHash, Set<Role> roles, boolean active,
                   boolean mustChangePassword, int failedAttempts, Instant lockedUntil, Instant lastLoginAt,
                   Instant createdAt, String createdBy) {

    public User {
        roles = Set.copyOf(roles);
    }

    public boolean has(Role r) {
        return roles.contains(Role.ADMIN) || roles.contains(r);
    }

    public boolean hasAny(Role... rs) {
        for (Role r : rs) if (has(r)) return true;
        return false;
    }

    public User with(String hash, boolean mustChange, int failed, Instant locked, Instant lastLogin) {
        return new User(id, username, displayName, hash, roles, active, mustChange, failed, locked, lastLogin, createdAt, createdBy);
    }

    public User withProfile(String name, Set<Role> newRoles, boolean isActive) {
        return new User(id, username, name, passwordHash, newRoles, isActive, mustChangePassword, failedAttempts, lockedUntil,
                lastLoginAt, createdAt, createdBy);
    }

    /** What the API shows about a user. */
    public record View(String id, String username, String displayName, Set<Role> roles, boolean active,
                       boolean mustChangePassword, Instant lastLoginAt, Instant createdAt) { }

    public View view() {
        return new View(id, username, displayName, roles, active, mustChangePassword, lastLoginAt, createdAt);
    }
}
