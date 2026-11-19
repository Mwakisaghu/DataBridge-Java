package com.databridge.app;

import com.databridge.app.http.Access;
import com.databridge.app.http.Endpoints;
import com.databridge.app.http.Response;
import com.databridge.core.security.Role;
import com.databridge.core.security.User;
import com.databridge.core.security.UserService;
import com.databridge.core.service.ValidationException;
import io.undertow.server.RoutingHandler;

import java.util.Set;

/** Sign-in, the signed-in user, and user administration. */
public final class AuthRoutes {

    public record LoginRequest(String username, String password) { }
    public record PasswordChange(String currentPassword, String newPassword) { }
    public record NewUser(String username, String displayName, Set<Role> roles) { }
    public record UserChange(String displayName, Set<Role> roles, Boolean active) { }
    /** The signed-in user and how the server signs people in. */
    public record Me(User.View user, String authMode) { }

    private final UserService users;
    private final String mode;

    public AuthRoutes(UserService users, String mode) {
        this.users = users;
        this.mode = mode;
    }

    public void register(RoutingHandler r, Endpoints e) {
        r.post("/api/auth/login", e.handler(Access.OPEN, q -> {
            LoginRequest b = q.body(LoginRequest.class);
            UserService.Login l = users.login(b.username(), b.password());
            q.startSession(l.token(), l.expiresAt());
            return Response.ok(new Me(l.user().view(), mode));
        }));
        r.post("/api/auth/logout", e.handler(Access.OPEN, q -> {
            users.logout(q.sessionToken());
            q.endSession();
            return Response.noContent();
        }));
        r.get("/api/auth/me", e.handler(Access.SELF, q -> Response.ok(new Me(q.user().view(), mode))));
        r.post("/api/auth/password", e.handler(Access.SELF, q -> {
            PasswordChange b = q.body(PasswordChange.class);
            users.changePassword(q.user().id(), b.currentPassword(), b.newPassword());
            return Response.ok(new Me(users.user(q.user().id()).view(), mode));
        }));

        r.get("/api/users", e.handler(Access.ADMIN, q -> Response.ok(users.list())));
        r.post("/api/users", e.handler(Access.ADMIN, q -> {
            NewUser b = q.body(NewUser.class);
            return Response.created(users.create(b.username(), b.displayName(), b.roles(), q.actor()));
        }));
        r.put("/api/users/{id}", e.handler(Access.ADMIN, q -> {
            UserChange b = q.body(UserChange.class);
            if (b.active() == null) throw new ValidationException("Say whether the user is active");
            return Response.ok(users.update(q.path("id"), b.displayName(), b.roles(), b.active(), q.actor()));
        }));
        r.post("/api/users/{id}/reset-password", e.handler(Access.ADMIN, q -> Response.ok(users.resetPassword(q.path("id"), q.actor()))));
    }
}
