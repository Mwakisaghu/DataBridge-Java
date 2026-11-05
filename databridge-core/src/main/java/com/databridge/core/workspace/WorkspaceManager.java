package com.databridge.core.workspace;

import com.databridge.core.setup.RetentionMode;
import com.databridge.core.setup.RetentionSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Creates and destroys workspaces according to the Retention Setup.
 *
 * <p>Safety rules: it only ever deletes folders that carry its own marker file, so a
 * misconfigured workspace root cannot wipe unrelated folders; it overwrites file
 * contents before deleting when {@code secureOverwrite} is on; and a shutdown hook
 * destroys any PURGE_ON_COMPLETE workspace still open when the JVM stops.
 */
public final class WorkspaceManager {

    static final String MARKER = ".databridge-workspace";
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceManager.class);
    private static final int CHUNK = 64 * 1024;

    private final Path root;
    private final RetentionSetup retention;
    private final Clock clock;
    private final Set<Workspace> open = ConcurrentHashMap.newKeySet();
    private volatile boolean hookInstalled;

    public WorkspaceManager(Path root, RetentionSetup retention) {
        this(root, retention, Clock.systemUTC());
    }

    public WorkspaceManager(Path root, RetentionSetup retention, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.retention = retention;
        this.clock = clock;
    }

    public Path root() { return root; }

    /** Opens a new, empty workspace. */
    public Workspace open() {
        Instant now = clock.instant();
        boolean purgeOnClose = retention.getMode() == RetentionMode.PURGE_ON_COMPLETE;
        Duration life = purgeOnClose
                ? Duration.ofHours(retention.getMaxRunHours())
                : Duration.ofHours(retention.getKeepHours());
        String id = UUID.randomUUID().toString();
        Path dir = root.resolve(id);
        try {
            Files.createDirectories(dir);
            Properties p = new Properties();
            p.setProperty("created", now.toString());
            p.setProperty("expiresAt", now.plus(life).toString());
            try (OutputStream out = Files.newOutputStream(dir.resolve(MARKER))) {
                p.store(out, "DataBridge workspace - destroyed automatically, do not store files here");
            }
        } catch (IOException e) {
            throw new WorkspaceException("Cannot create workspace under " + root, e);
        }
        Workspace ws = new Workspace(this, id, dir, now.plus(life), purgeOnClose);
        if (purgeOnClose) {
            open.add(ws);
            installShutdownHook();
        }
        return ws;
    }

    /** Destroys one workspace now, whatever its retention. */
    public PurgeReport purge(Workspace ws) {
        open.remove(ws);
        return destroy(ws.dir());
    }

    /** Destroys every workspace whose expiry has passed. */
    public PurgeReport purgeExpired() {
        Instant now = clock.instant();
        PurgeReport total = PurgeReport.empty();
        for (Path dir : workspaceDirs()) {
            Instant expires = readExpiry(dir);
            if (expires == null || !expires.isAfter(now)) total = total.plus(destroy(dir));
        }
        return total;
    }

    /** Destroys every workspace under the root. Use to clear all client data. */
    public PurgeReport purgeAll() {
        PurgeReport total = PurgeReport.empty();
        for (Path dir : workspaceDirs()) total = total.plus(destroy(dir));
        return total;
    }

    /** Number of workspaces currently on disk. */
    public int count() {
        return workspaceDirs().size();
    }

    void released(Workspace ws) {
        open.remove(ws);
    }

    private List<Path> workspaceDirs() {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory).filter(d -> Files.isRegularFile(d.resolve(MARKER))).toList();
        } catch (IOException e) {
            throw new WorkspaceException("Cannot list workspaces under " + root, e);
        }
    }

    private Instant readExpiry(Path dir) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve(MARKER))) {
            p.load(in);
            return Instant.parse(p.getProperty("expiresAt"));
        } catch (IOException | RuntimeException e) {
            return null; // unreadable marker: treat as expired
        }
    }

    private PurgeReport destroy(Path dir) {
        if (!Files.isRegularFile(dir.resolve(MARKER))) {
            if (Files.exists(dir)) throw new WorkspaceException("Refusing to delete a folder without a workspace marker");
            return PurgeReport.empty();
        }
        if (!dir.toAbsolutePath().normalize().startsWith(root)) {
            throw new WorkspaceException("Refusing to delete a folder outside the workspace root");
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        } catch (IOException e) {
            throw new WorkspaceException("Cannot read workspace for purge", e);
        }
        int files = 0;
        long bytes = 0;
        for (Path p : paths) {
            try {
                if (Files.isRegularFile(p) && !p.getFileName().toString().equals(MARKER)) {
                    long size = Files.size(p);
                    if (retention.isSecureOverwrite()) overwrite(p, size);
                    files++;
                    bytes += size;
                }
                Files.deleteIfExists(p);
            } catch (IOException e) {
                throw new WorkspaceException("Cannot purge workspace file", e);
            }
        }
        LOG.info("Purged workspace: {} file(s), {} byte(s)", files, bytes);
        return new PurgeReport(1, files, bytes);
    }

    private static void overwrite(Path file, long size) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ByteBuffer zeros = ByteBuffer.allocate(CHUNK);
            long written = 0;
            while (written < size) {
                zeros.clear();
                zeros.limit((int) Math.min(CHUNK, size - written));
                written += ch.write(zeros, written);
            }
            ch.force(true);
        }
    }

    private void installShutdownHook() {
        if (hookInstalled) return;
        synchronized (this) {
            if (hookInstalled) return;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (Workspace ws : List.copyOf(open)) {
                    try {
                        purge(ws);
                    } catch (RuntimeException e) {
                        LOG.warn("Workspace purge at shutdown failed: {}", e.getMessage());
                    }
                }
            }, "databridge-workspace-purge"));
            hookInstalled = true;
        }
    }
}
