package com.databridge.core.port;

import com.databridge.core.domain.DatasetVersion;
import com.databridge.core.domain.TemplateKind;

import java.util.List;
import java.util.Optional;

public interface DatasetRepository {
    void insert(DatasetVersion dataset);
    Optional<DatasetVersion> find(String id);
    List<DatasetVersion> listByRun(String runId);
    /** Highest version number for this run and template kind, or 0. */
    int maxVersion(String runId, TemplateKind kind);
}
