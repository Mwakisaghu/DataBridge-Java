package com.databridge.core.port;

import com.databridge.core.domain.Project;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository {
    void insert(Project project);
    Optional<Project> find(String id);
    Optional<Project> findByCode(String clientId, String code);
    List<Project> listByClient(String clientId);
}
