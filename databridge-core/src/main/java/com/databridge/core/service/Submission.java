package com.databridge.core.service;

import com.databridge.core.domain.TemplateKind;
import com.databridge.core.workspace.Workspace;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workbooks of one run waiting to be validated together, held in their own workspace. Lives in
 * memory only: if the server stops, the workspace is swept like any other abandoned one.
 */
public final class Submission {

    /** A file received into the submission. {@code error} is set when it could not be received. */
    public record File(TemplateKind kind, String fileName, String sha256, long sizeBytes, String error) { }

    /** What the API shows: no paths. */
    public record View(String id, String runId, Instant createdAt, Instant expiresAt, List<File> files) { }

    final String id;
    final String runId;
    final String createdBy;
    final Instant createdAt;
    final Workspace workspace;
    final Map<TemplateKind, File> files = new LinkedHashMap<>();
    final Map<TemplateKind, Path> paths = new LinkedHashMap<>();

    Submission(String id, String runId, String createdBy, Instant createdAt, Workspace workspace) {
        this.id = id;
        this.runId = runId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.workspace = workspace;
    }

    public synchronized View view() {
        return new View(id, runId, createdAt, workspace.expiresAt(), new ArrayList<>(files.values()));
    }
}
