package com.databridge.core.port;

import com.databridge.core.domain.FindingCount;
import com.databridge.core.domain.FindingFilter;
import com.databridge.core.domain.StoredFinding;

import java.util.List;
import java.util.Optional;

public interface FindingRepository {
    void insertAll(List<StoredFinding> findings);
    Optional<StoredFinding> find(String id);
    /** Updates the status and waiver fields. */
    void update(StoredFinding finding);
    /** In severity, sheet and row order. */
    List<StoredFinding> list(String checkId, FindingFilter filter, int offset, int limit);
    int count(String checkId, FindingFilter filter);
    List<FindingCount> counts(String checkId);
}
