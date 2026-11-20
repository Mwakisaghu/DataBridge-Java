package com.databridge.core.port;

import com.databridge.core.security.Session;
import com.databridge.core.security.User;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository {
    void insert(User user);
    void update(User user);
    Optional<User> find(String id);
    Optional<User> findByUsername(String username);
    List<User> list();
    int count();

    void insertSession(Session session);
    Optional<Session> findSession(String tokenHash);
    void touchSession(String tokenHash, Instant lastSeenAt);
    void deleteSession(String tokenHash);
    /** Signs a user out everywhere. */
    void deleteSessionsOf(String userId);
    int deleteExpiredSessions(Instant now);
}
