package com.databridge.core.port;

import com.databridge.core.validation.RuleDef;

import java.time.Instant;
import java.util.List;

/** Stored Rule Setup entries; they override shipped defaults with the same id. */
public interface RuleRepository {
    List<RuleDef> list();
    void save(RuleDef rule, String actor, Instant at);
    boolean delete(String id);
}
