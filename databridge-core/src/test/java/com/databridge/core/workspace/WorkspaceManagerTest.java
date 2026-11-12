package com.databridge.core.workspace;

import com.databridge.core.setup.RetentionMode;
import com.databridge.core.setup.RetentionSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceManagerTest {

    @TempDir Path tmp;

    /** A clock the test can move forward. */
    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2027-01-31T08:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private Path clientFile(String name, String content) throws Exception {
        Path f = tmp.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    private static RetentionSetup retention(RetentionMode mode) {
        RetentionSetup r = new RetentionSetup();
        r.setMode(mode);
        return r;
    }

    @Test
    void purgeOnCompleteDestroysWorkspaceWhenClosed() throws Exception {
        WorkspaceManager m = new WorkspaceManager(tmp.resolve("ws"), retention(RetentionMode.PURGE_ON_COMPLETE));
        Path copy;
        Path dir;
        try (Workspace ws = m.open()) {
            copy = ws.importFile(clientFile("members.xlsx", "client data"));
            dir = ws.dir();
            assertTrue(Files.exists(copy));
        }
        assertFalse(Files.exists(copy));
        assertFalse(Files.exists(dir));
        assertEquals(0, m.count());
    }

    @Test
    void keepForHoursSurvivesCloseUntilExpiry() throws Exception {
        MovableClock clock = new MovableClock();
        RetentionSetup r = retention(RetentionMode.KEEP_FOR_HOURS);
        r.setKeepHours(24);
        WorkspaceManager m = new WorkspaceManager(tmp.resolve("ws"), r, clock);
        try (Workspace ws = m.open()) {
            ws.importFile(clientFile("loans.xlsx", "client data"));
        }
        assertEquals(1, m.count());
        assertEquals(0, m.purgeExpired().workspaces());

        clock.advance(Duration.ofHours(25));
        assertEquals(1, m.purgeExpired().workspaces());
        assertEquals(0, m.count());
    }

    @Test
    void workspaceLeftByACrashIsSweptAfterMaxRunHours() throws Exception {
        MovableClock clock = new MovableClock();
        RetentionSetup r = retention(RetentionMode.PURGE_ON_COMPLETE);
        r.setMaxRunHours(6);
        WorkspaceManager m = new WorkspaceManager(tmp.resolve("ws"), r, clock);
        Workspace ws = m.open(); // never closed, as if the process died
        ws.importFile(clientFile("savings.xlsx", "client data"));

        clock.advance(Duration.ofHours(5));
        assertEquals(0, m.purgeExpired().workspaces());
        clock.advance(Duration.ofHours(2));
        assertEquals(1, m.purgeExpired().workspaces());
    }

    @Test
    void purgeAllClearsEveryWorkspace() throws Exception {
        WorkspaceManager m = new WorkspaceManager(tmp.resolve("ws"), retention(RetentionMode.KEEP_FOR_HOURS));
        for (int i = 0; i < 3; i++) {
            try (Workspace ws = m.open()) {
                ws.importFile(clientFile("f" + i + ".xlsx", "data " + i));
            }
        }
        PurgeReport p = m.purgeAll();
        assertEquals(3, p.workspaces());
        assertEquals(3, p.files());
        assertEquals(0, m.count());
    }

    @Test
    void neverTouchesFoldersWithoutItsMarker() throws Exception {
        Path root = tmp.resolve("ws");
        Path foreign = Files.createDirectories(root.resolve("someone-elses-folder"));
        Files.writeString(foreign.resolve("keep.txt"), "not ours");
        WorkspaceManager m = new WorkspaceManager(root, retention(RetentionMode.KEEP_FOR_HOURS));
        m.open().close();

        m.purgeAll();
        assertTrue(Files.exists(foreign.resolve("keep.txt")));
        assertEquals(0, m.count());
    }

    @Test
    void reportCountsBytesButNotNames() throws Exception {
        WorkspaceManager m = new WorkspaceManager(tmp.resolve("ws"), retention(RetentionMode.KEEP_FOR_HOURS));
        try (Workspace ws = m.open()) {
            ws.importFile(clientFile("a.xlsx", "12345"));
            ws.importFile(clientFile("b.xlsx", "1234567890"));
        }
        PurgeReport p = m.purgeAll();
        assertEquals(2, p.files());
        assertEquals(15, p.bytes());
    }

    @Test
    void closedWorkspaceRejectsNewFiles() throws Exception {
        WorkspaceManager m = new WorkspaceManager(tmp.resolve("ws"), retention(RetentionMode.PURGE_ON_COMPLETE));
        Workspace ws = m.open();
        ws.close();
        Path f = clientFile("late.xlsx", "x");
        assertThrows(WorkspaceException.class, () -> ws.importFile(f));
    }

    @Test
    void fileNamesCannotEscapeTheWorkspace() {
        String safe = Workspace.safeName("../../etc/passwd");
        assertFalse(safe.contains("/"));
        assertFalse(safe.startsWith("."));
    }
}
