package com.databridge.app.http;

import com.databridge.core.security.Role;

import java.util.Set;

/**
 * Who may call an endpoint. {@code roles} empty means any signed-in user. {@code open} endpoints
 * need no sign-in (health, sign-in itself). {@code beforePasswordChange} endpoints stay usable while
 * a user still has a temporary password.
 */
public record Access(Set<Role> roles, boolean open, boolean beforePasswordChange) {

    public static final Access OPEN = new Access(Set.of(), true, true);
    public static final Access ANY = new Access(Set.of(), false, false);
    public static final Access SELF = new Access(Set.of(), false, true);
    public static final Access CONSULTANT = new Access(Set.of(Role.CONSULTANT), false, false);
    public static final Access REVIEWER = new Access(Set.of(Role.REVIEWER), false, false);
    public static final Access FINANCE = new Access(Set.of(Role.FINANCE), false, false);
    public static final Access REVIEWER_OR_FINANCE = new Access(Set.of(Role.REVIEWER, Role.FINANCE), false, false);
    public static final Access ADMIN = new Access(Set.of(Role.ADMIN), false, false);
}
