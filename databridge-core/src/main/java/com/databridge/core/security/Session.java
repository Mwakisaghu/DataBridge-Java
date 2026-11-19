package com.databridge.core.security;

import java.time.Instant;

/** A signed-in session. Only the SHA-256 of the token is stored, so a database copy cannot be replayed. */
public record Session(String tokenHash, String userId, Instant createdAt, Instant expiresAt, Instant lastSeenAt) { }
