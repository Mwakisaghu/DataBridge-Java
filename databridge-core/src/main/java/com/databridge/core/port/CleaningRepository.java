package com.databridge.core.port;

import com.databridge.core.cleaning.CleanChange;
import com.databridge.core.cleaning.CleanRule;
import com.databridge.core.domain.CleaningJob;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Stored Cleaning Setup entries, and cleaning jobs with their change logs. */
public interface CleaningRepository {
    List<CleanRule> rules();
    void saveRule(CleanRule r, String actor, Instant at);
    boolean deleteRule(String id);

    void insertJob(CleaningJob job, List<CleanChange> changes);
    List<CleaningJob> jobs(String runId);
    Optional<CleaningJob> job(String id);
    List<CleanChange> changes(String jobId);
}
