package com.databridge.core.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * A short-lived folder that holds client files while they are processed. It is the
 * only place client data touches disk. Use it in try-with-resources: under the
 * default PURGE_ON_COMPLETE retention, closing it destroys the folder.
 */
public final class Workspace implements AutoCloseable {

    private final WorkspaceManager manager;
    private final String id;
    private final Path dir;
    private final Instant expiresAt;
    private final boolean purgeOnClose;
    private boolean closed;

    Workspace(WorkspaceManager manager, String id, Path dir, Instant expiresAt, boolean purgeOnClose) {
        this.manager = manager;
        this.id = id;
        this.dir = dir;
        this.expiresAt = expiresAt;
        this.purgeOnClose = purgeOnClose;
    }

    public String id() { return id; }
    public Path dir() { return dir; }
    public Instant expiresAt() { return expiresAt; }
    public boolean purgeOnClose() { return purgeOnClose; }

    /** Copies a client file into the workspace and returns the copy's path. */
    public Path importFile(Path source) {
        ensureOpen();
        String name = safeName(source.getFileName().toString());
        Path target = dir.resolve(name);
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new WorkspaceException("Cannot copy " + name + " into workspace " + id, e);
        }
    }

    /** A path inside the workspace for an intermediate file. */
    public Path file(String name) {
        ensureOpen();
        return dir.resolve(safeName(name));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        manager.released(this);
        if (purgeOnClose) manager.purge(this);
    }

    private void ensureOpen() {
        if (closed) throw new WorkspaceException("Workspace " + id + " is closed");
    }

    static String safeName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isBlank() || cleaned.startsWith(".")) cleaned = "file" + cleaned;
        return cleaned;
    }
}
