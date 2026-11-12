package com.databridge.core.port;

import com.databridge.core.domain.MigrationRun;

import java.util.List;
import java.util.Optional;

public interface RunRepository {
    void insert(MigrationRun run);
    void updateState(MigrationRun run);
    Optional<MigrationRun> find(String id);
    List<MigrationRun> listByProject(String projectId);
    /** Highest run number in the project, or 0. */
    int maxRunNo(String projectId);
}
