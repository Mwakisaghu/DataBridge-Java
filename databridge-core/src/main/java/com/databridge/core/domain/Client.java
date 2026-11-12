package com.databridge.core.domain;

import java.time.Instant;

/** A client organisation, e.g. a SACCO. Holds no member data. */
public record Client(String id, String code, String name, Instant createdAt, String createdBy) { }
