package com.databridge.core.security;

import com.databridge.core.port.UserRepository;
import com.databridge.core.service.AuditLog;
import com.databridge.core.service.NotFoundException;
import com.databridge.core.service.ValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Local accounts and sessions. Passwords are PBKDF2-hashed; five wrong passwords lock an account
 * for fifteen minutes; sessions end after {@code idle} without use or {@code absolute} after
 * sign-in. Session tokens are random 256-bit values; only their SHA-256 is stored.
 */
public final class UserService {

    /** A successful sign-in: the token for the session cookie, and the user. */
    public record Login(String token, User user, Instant expiresAt) { }

    /** A new or reset account and its one-time temporary password. */
    public record Issued(User.View user, String temporaryPassword) { }

    public static final int MIN_PASSWORD = 12;
    public static final int MAX_FAILED = 5;
    public static final Duration LOCK = Duration.ofMinutes(15);
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._@-]{1,59}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BAD_LOGIN = "Wrong user name or password";

    private final UserRepository repo;
    private final PasswordHasher hasher;
    private final AuditLog audit;
    private final Clock clock;
    private final Duration idle;
    private final Duration absolute;

    public UserService(UserRepository repo, PasswordHasher hasher, AuditLog audit, Clock clock, Duration idle, Duration absolute) {
        this.repo = repo;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
        this.idle = idle;
        this.absolute = absolute;
    }

    /**
     * Creates the first administrator when there are no users yet. Returns the password to show
     * once: {@code initialPassword} when given (it must meet the policy), otherwise a random one.
     */
    public Optional<String> bootstrap(String initialPassword) {
        if (repo.count() > 0) return Optional.empty();
        String pw = initialPassword == null || initialPassword.isBlank() ? PasswordHasher.temporaryPassword() : initialPassword;
        policy(pw);
        Instant now = now();
        repo.insert(new User(UUID.randomUUID().toString(), "admin", "Administrator", hasher.hash(pw.toCharArray()),
                Set.of(Role.ADMIN), true, true, 0, null, null, now, "system"));
        audit.record("system", "USER_CREATED", "USER", "admin", "First administrator, roles [ADMIN]");
        return Optional.of(pw);
    }

    public Login login(String username, String password) {
        String u = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        Optional<User> found = repo.findByUsername(u);
        Instant now = now();
        if (found.isEmpty() || !found.get().active()) {
            hasher.verify((password == null ? "" : password).toCharArray(), DUMMY); // same work either way
            audit.record(u.isEmpty() ? "unknown" : u, "LOGIN_FAILED", "USER", u, found.isEmpty() ? "No such user" : "Account disabled");
            throw new AuthException(BAD_LOGIN, false);
        }
        User user = found.get();
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            throw new AuthException("Account locked after " + MAX_FAILED + " wrong passwords; try again after "
                    + user.lockedUntil().truncatedTo(ChronoUnit.MINUTES) + " (UTC) or ask an administrator to reset it", false);
        }
        if (password == null || !hasher.verify(password.toCharArray(), user.passwordHash())) {
            int failed = user.failedAttempts() + 1;
            boolean lock = failed >= MAX_FAILED;
            repo.update(user.with(user.passwordHash(), user.mustChangePassword(), lock ? 0 : failed, lock ? now.plus(LOCK) : null,
                    user.lastLoginAt()));
            audit.record(user.username(), lock ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", "USER", user.username(),
                    lock ? "Locked for " + LOCK.toMinutes() + " minutes" : "Wrong password (" + failed + " of " + MAX_FAILED + ")");
            throw new AuthException(BAD_LOGIN, false);
        }
        User signedIn = user.with(user.passwordHash(), user.mustChangePassword(), 0, null, now);
        repo.update(signedIn);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = now.plus(absolute);
        repo.insertSession(new Session(sha256(token), user.id(), now, expires, now));
        audit.record(user.username(), "LOGIN", "USER", user.username(), "Signed in");
        return new Login(token, signedIn, expires);
    }

    /** The user of a live session, or empty. Refreshes the session's last use at most once a minute. */
    public Optional<User> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String h = sha256(token);
        Optional<Session> s = repo.findSession(h);
        if (s.isEmpty()) return Optional.empty();
        Instant now = now();
        Session session = s.get();
        if (!session.expiresAt().isAfter(now) || !session.lastSeenAt().plus(idle).isAfter(now)) {
            repo.deleteSession(h);
            return Optional.empty();
        }
        Optional<User> u = repo.find(session.userId()).filter(User::active);
        if (u.isEmpty()) {
            repo.deleteSession(h);
            return Optional.empty();
        }
        if (Duration.between(session.lastSeenAt(), now).toSeconds() >= 60) repo.touchSession(h, now);
        return u;
    }

    public void logout(String token) {
        if (token == null) return;
        String h = sha256(token);
        repo.findSession(h).flatMap(s -> repo.find(s.userId()))
                .ifPresent(u -> audit.record(u.username(), "LOGOUT", "USER", u.username(), "Signed out"));
        repo.deleteSession(h);
    }

    public void changePassword(String userId, String current, String next) {
        User u = user(userId);
        if (current == null || !hasher.verify(current.toCharArray(), u.passwordHash())) {
            throw new ValidationException("The current password is not right");
        }
        policy(next);
        if (next.equals(current)) throw new ValidationException("Choose a password different from the current one");
        repo.update(u.with(hasher.hash(next.toCharArray()), false, 0, null, u.lastLoginAt()));
        audit.record(u.username(), "PASSWORD_CHANGED", "USER", u.username(), "Changed own password");
    }

    public Issued create(String username, String displayName, Set<Role> roles, String actor) {
        String who = AuditLog.actor(actor);
        String u = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(u).matches()) {
            throw new ValidationException("User name must be 2-60 characters: lower-case letters, digits, '.', '_', '-' or '@'");
        }
        if (repo.findByUsername(u).isPresent()) throw new ValidationException("User " + u + " already exists");
        String name = name(displayName);
        if (roles == null || roles.isEmpty()) throw new ValidationException("Give the user at least one role");
        String pw = PasswordHasher.temporaryPassword();
        User user = new User(UUID.randomUUID().toString(), u, name, hasher.hash(pw.toCharArray()), roles, true, true, 0, null, null,
                now(), who);
        repo.insert(user);
        audit.record(who, "USER_CREATED", "USER", u, "Roles " + roles);
        return new Issued(user.view(), pw);
    }

    public User.View update(String id, String displayName, Set<Role> roles, boolean active, String actor) {
        String who = AuditLog.actor(actor);
        User u = user(id);
        if (roles == null || roles.isEmpty()) throw new ValidationException("Give the user at least one role");
        boolean losesAdmin = u.roles().contains(Role.ADMIN) && u.active() && (!roles.contains(Role.ADMIN) || !active);
        if (losesAdmin && activeAdmins() <= 1) {
            throw new ValidationException("This is the last active administrator; make someone else an administrator first");
        }
        User changed = u.withProfile(name(displayName), roles, active);
        repo.update(changed);
        if (!active) repo.deleteSessionsOf(u.id());
        audit.record(who, "USER_CHANGED", "USER", u.username(), "Roles " + roles + (active ? "" : ", disabled"));
        return changed.view();
    }

    public Issued resetPassword(String id, String actor) {
        String who = AuditLog.actor(actor);
        User u = user(id);
        String pw = PasswordHasher.temporaryPassword();
        repo.update(u.with(hasher.hash(pw.toCharArray()), true, 0, null, u.lastLoginAt()));
        repo.deleteSessionsOf(u.id());
        audit.record(who, "PASSWORD_RESET", "USER", u.username(), "Temporary password issued; signed out everywhere");
        return new Issued(user(id).view(), pw);
    }

    public List<User.View> list() {
        return repo.list().stream().map(User::view).toList();
    }

    public User user(String id) {
        return repo.find(id).orElseThrow(() -> new NotFoundException("User", id));
    }

    public int sweepSessions() {
        return repo.deleteExpiredSessions(now());
    }

    private long activeAdmins() {
        return repo.list().stream().filter(x -> x.active() && x.roles().contains(Role.ADMIN)).count();
    }

    static void policy(String pw) {
        if (pw == null || pw.length() < MIN_PASSWORD) {
            throw new ValidationException("Passwords need at least " + MIN_PASSWORD + " characters");
        }
        if (pw.length() > 200) throw new ValidationException("Passwords may have at most 200 characters");
        boolean letter = pw.chars().anyMatch(Character::isLetter);
        boolean other = pw.chars().anyMatch(c -> !Character.isLetter(c));
        if (!letter || !other) throw new ValidationException("Use letters and at least one digit or symbol");
    }

    private static String name(String displayName) {
        if (displayName == null || displayName.isBlank()) throw new ValidationException("A display name is required");
        String n = displayName.trim();
        if (n.length() > 100) throw new ValidationException("Display name is longer than 100 characters");
        return n;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A real hash of a random password, so unknown users cost the same time as known ones. */
    private static final String DUMMY = new PasswordHasher(PasswordHasher.ITERATIONS).hash(PasswordHasher.temporaryPassword().toCharArray());
}
