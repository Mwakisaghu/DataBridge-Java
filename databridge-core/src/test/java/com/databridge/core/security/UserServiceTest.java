package com.databridge.core.security;

import com.databridge.core.port.memory.InMemoryRepositories;
import com.databridge.core.service.AuditLog;
import com.databridge.core.service.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServiceTest {

    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2027-01-31T08:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();
    private final InMemoryRepositories.Users repo = new InMemoryRepositories.Users();
    private final InMemoryRepositories.Audit auditRepo = new InMemoryRepositories.Audit();
    private final UserService users = new UserService(repo, new PasswordHasher(1000), new AuditLog(auditRepo, clock), clock,
            Duration.ofHours(2), Duration.ofHours(12));

    private String adminId() {
        return users.list().stream().filter(u -> u.username().equals("admin")).findFirst().orElseThrow().id();
    }

    @Test
    void bootstrapCreatesOneAdminWhoMustChangeTheTemporaryPassword() {
        String pw = users.bootstrap(null).orElseThrow();
        assertTrue(pw.length() >= UserService.MIN_PASSWORD);
        assertTrue(users.bootstrap(null).isEmpty(), "only when there are no users");
        UserService.Login l = users.login("ADMIN ", pw);
        assertTrue(l.user().mustChangePassword());
        assertEquals(l.user().id(), users.authenticate(l.token()).orElseThrow().id());
        assertThrows(ValidationException.class, () -> users.changePassword(l.user().id(), pw, "short1"));
        users.changePassword(l.user().id(), pw, "a-much-longer-pass-1");
        assertFalse(users.user(l.user().id()).mustChangePassword());
    }

    @Test
    void passwordsAreHashedNotStored() {
        String pw = users.bootstrap("correct-horse-battery-9").orElseThrow();
        User u = users.user(adminId());
        assertNotEquals(pw, u.passwordHash());
        assertFalse(u.passwordHash().contains(pw));
    }

    @Test
    void fiveWrongPasswordsLockTheAccountForFifteenMinutes() {
        String pw = users.bootstrap("correct-horse-battery-9").orElseThrow();
        for (int i = 0; i < UserService.MAX_FAILED; i++) {
            assertThrows(AuthException.class, () -> users.login("admin", "wrong-password-123"));
        }
        AuthException locked = assertThrows(AuthException.class, () -> users.login("admin", pw));
        assertTrue(locked.getMessage().contains("locked"), locked.getMessage());
        clock.advance(UserService.LOCK.plusSeconds(1));
        users.login("admin", pw);
    }

    @Test
    void unknownUserAndWrongPasswordGiveTheSameMessage() {
        users.bootstrap("correct-horse-battery-9");
        String a = assertThrows(AuthException.class, () -> users.login("nobody", "whatever-123456")).getMessage();
        String b = assertThrows(AuthException.class, () -> users.login("admin", "whatever-123456")).getMessage();
        assertEquals(a, b);
    }

    @Test
    void sessionsExpireWhenIdleAndAtTheAbsoluteLimit() {
        String pw = users.bootstrap("correct-horse-battery-9").orElseThrow();
        String idle = users.login("admin", pw).token();
        clock.advance(Duration.ofHours(2).plusSeconds(1));
        assertTrue(users.authenticate(idle).isEmpty());

        String busy = users.login("admin", pw).token();
        for (int i = 0; i < 12; i++) {
            clock.advance(Duration.ofMinutes(59));
            assertTrue(users.authenticate(busy).isPresent(), "hour " + i);
        }
        clock.advance(Duration.ofMinutes(13));
        assertTrue(users.authenticate(busy).isEmpty(), "absolute limit");
    }

    @Test
    void logoutEndsTheSession() {
        String pw = users.bootstrap("correct-horse-battery-9").orElseThrow();
        String t = users.login("admin", pw).token();
        users.logout(t);
        assertTrue(users.authenticate(t).isEmpty());
    }

    @Test
    void adminsManageUsersButTheLastAdminCannotBeRemoved() {
        users.bootstrap("correct-horse-battery-9");
        UserService.Issued r = users.create("Jane.Doe", "Jane Doe", Set.of(Role.REVIEWER), "admin");
        assertEquals("jane.doe", r.user().username());
        assertThrows(ValidationException.class, () -> users.create("jane.doe", "Again", Set.of(Role.VIEWER), "admin"));
        assertThrows(ValidationException.class, () -> users.create("x y", "Bad", Set.of(Role.VIEWER), "admin"));
        assertThrows(ValidationException.class, () -> users.update(adminId(), "Administrator", Set.of(Role.VIEWER), true, "admin"));

        String t = users.login("jane.doe", r.temporaryPassword()).token();
        users.update(r.user().id(), "Jane Doe", Set.of(Role.REVIEWER), false, "admin");
        assertTrue(users.authenticate(t).isEmpty(), "disabling signs the user out");
        assertThrows(AuthException.class, () -> users.login("jane.doe", r.temporaryPassword()));

        users.update(r.user().id(), "Jane Doe", Set.of(Role.REVIEWER, Role.ADMIN), true, "admin");
        UserService.Issued reset = users.resetPassword(r.user().id(), "admin");
        assertTrue(users.login("jane.doe", reset.temporaryPassword()).user().mustChangePassword());
        assertTrue(auditRepo.list(null, null, 1000).stream().anyMatch(e -> e.action().equals("PASSWORD_RESET")));
        assertTrue(auditRepo.list(null, null, 1000).stream().noneMatch(e -> e.details() != null && e.details().contains(reset.temporaryPassword())),
                "passwords never reach the audit trail");
    }

    @Test
    void adminRoleImpliesEveryOtherRole() {
        users.bootstrap("correct-horse-battery-9");
        User admin = users.user(adminId());
        assertTrue(admin.has(Role.REVIEWER) && admin.has(Role.FINANCE) && admin.has(Role.CONSULTANT));
    }
}
