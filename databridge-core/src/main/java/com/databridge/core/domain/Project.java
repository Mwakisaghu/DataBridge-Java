package com.databridge.core.domain;

import java.time.Instant;

/** One migration project for a client, targeting one Business Central company. */
public record Project(String id, String clientId, String code, String name, String bcCompany,
                      Instant createdAt, String createdBy) { }
