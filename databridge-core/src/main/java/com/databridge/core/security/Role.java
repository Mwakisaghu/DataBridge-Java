package com.databridge.core.security;

/**
 * What a user may do. ADMIN may do everything; the maker-checker rule still applies to admins,
 * because it compares people, not roles.
 */
public enum Role {
    /** Users, setups, rules, clearing client files. */
    ADMIN,
    /** Clients, projects, runs, submissions, cleaning, packages; requests waivers. */
    CONSULTANT,
    /** Decides waivers; reviews runs for sign-off. */
    REVIEWER,
    /** Explains and approves reconciliation variances; approves run sign-off. */
    FINANCE,
    /** Reads everything, changes nothing. */
    VIEWER
}
