package com.databridge.core.port;

import com.databridge.core.domain.ValidationCheck;

import java.util.List;
import java.util.Optional;

public interface CheckRepository {
    void insert(ValidationCheck check);
    Optional<ValidationCheck> find(String id);
    /** Newest first. */
    List<ValidationCheck> listByRun(String runId);
    /** Highest check number in the run, or 0. */
    int maxCheckNo(String runId);
}
