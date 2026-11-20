package com.databridge.store.jdbc;

import com.databridge.core.port.UserRepository;
import com.databridge.core.security.Role;
import com.databridge.core.security.Session;
import com.databridge.core.security.User;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Users and sessions over JDBC. Portable SQL. */
public final class JdbcUserRepository implements UserRepository {

    private static final String COLS = "id, username, display_name, password_hash, roles, active, must_change_password, "
            + "failed_attempts, locked_until, last_login_at, created_at, created_by";
    private final Jdbc jdbc;

    public JdbcUserRepository(DataSource ds) {
        this.jdbc = new Jdbc(ds);
    }

    @Override public void insert(User u) {
        jdbc.update("INSERT INTO app_user (" + COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", u.id(), u.username(),
                u.displayName(), u.passwordHash(), roles(u.roles()), u.active(), u.mustChangePassword(), u.failedAttempts(),
                u.lockedUntil(), u.lastLoginAt(), u.createdAt(), u.createdBy());
    }

    @Override public void update(User u) {
        int n = jdbc.update("UPDATE app_user SET display_name = ?, password_hash = ?, roles = ?, active = ?, must_change_password = ?, "
                + "failed_attempts = ?, locked_until = ?, last_login_at = ? WHERE id = ?", u.displayName(), u.passwordHash(),
                roles(u.roles()), u.active(), u.mustChangePassword(), u.failedAttempts(), u.lockedUntil(), u.lastLoginAt(), u.id());
        if (n != 1) throw new IllegalStateException("No user " + u.id());
    }

    @Override public Optional<User> find(String id) {
        return jdbc.one("SELECT " + COLS + " FROM app_user WHERE id = ?", JdbcUserRepository::map, id);
    }

    @Override public Optional<User> findByUsername(String username) {
        return jdbc.one("SELECT " + COLS + " FROM app_user WHERE username = ?", JdbcUserRepository::map, username);
    }

    @Override public List<User> list() {
        return jdbc.query("SELECT " + COLS + " FROM app_user ORDER BY username", JdbcUserRepository::map);
    }

    @Override public int count() {
        return jdbc.one("SELECT COUNT(*) AS n FROM app_user", rs -> rs.getInt("n")).orElse(0);
    }

    @Override public void insertSession(Session s) {
        jdbc.update("INSERT INTO app_session (token_hash, user_id, created_at, expires_at, last_seen_at) VALUES (?, ?, ?, ?, ?)",
                s.tokenHash(), s.userId(), s.createdAt(), s.expiresAt(), s.lastSeenAt());
    }

    @Override public Optional<Session> findSession(String h) {
        return jdbc.one("SELECT token_hash, user_id, created_at, expires_at, last_seen_at FROM app_session WHERE token_hash = ?",
                rs -> new Session(rs.getString("token_hash"), rs.getString("user_id"), Jdbc.instant(rs, "created_at"),
                        Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "last_seen_at")), h);
    }

    @Override public void touchSession(String h, Instant at) {
        jdbc.update("UPDATE app_session SET last_seen_at = ? WHERE token_hash = ?", at, h);
    }

    @Override public void deleteSession(String h) {
        jdbc.update("DELETE FROM app_session WHERE token_hash = ?", h);
    }

    @Override public void deleteSessionsOf(String userId) {
        jdbc.update("DELETE FROM app_session WHERE user_id = ?", userId);
    }

    @Override public int deleteExpiredSessions(Instant now) {
        return jdbc.update("DELETE FROM app_session WHERE expires_at <= ?", now);
    }

    private static String roles(Set<Role> roles) {
        return roles.stream().map(Role::name).sorted().collect(Collectors.joining(","));
    }

    static User map(ResultSet rs) throws SQLException {
        Set<Role> roles = new TreeSet<>();
        Arrays.stream(rs.getString("roles").split(",")).filter(r -> !r.isBlank()).forEach(r -> roles.add(Role.valueOf(r.trim())));
        return new User(rs.getString("id"), rs.getString("username"), rs.getString("display_name"), rs.getString("password_hash"),
                roles, rs.getBoolean("active"), rs.getBoolean("must_change_password"), rs.getInt("failed_attempts"),
                Jdbc.instant(rs, "locked_until"), Jdbc.instant(rs, "last_login_at"), Jdbc.instant(rs, "created_at"),
                rs.getString("created_by"));
    }
}
