package com.databridge.app.http;

import com.databridge.core.security.AuthException;
import com.databridge.core.security.Role;
import com.databridge.core.security.User;
import com.databridge.core.security.UserService;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.AttachmentKey;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Resolves who is calling and whether they may. In LOCAL mode the caller is the user of the
 * session cookie. In NONE mode (development on 127.0.0.1 only) the caller names themselves in
 * the X-DataBridge-User header and holds every role.
 *
 * <p>State-changing requests must carry the {@code X-DataBridge-Client} header. A browser cannot
 * add it to a cross-site request without a CORS preflight, which this server never grants, so
 * this, with SameSite=Strict cookies, blocks cross-site request forgery.
 */
public final class Auth {

    public enum Mode { LOCAL, NONE }

    public static final String COOKIE = "DB_SESSION";
    public static final String CLIENT_HEADER = "X-DataBridge-Client";
    static final AttachmentKey<User> USER = AttachmentKey.create(User.class);

    private final Mode mode;
    private final UserService users;
    private final boolean secureCookie;

    public Auth(Mode mode, UserService users, boolean secureCookie) {
        this.mode = mode;
        this.users = users;
        this.secureCookie = secureCookie;
    }

    public Mode mode() {
        return mode;
    }

    /** Checks the caller against {@code access} and attaches the user; throws AuthException otherwise. */
    void check(HttpServerExchange ex, Access access) {
        String method = ex.getRequestMethod().toString();
        boolean safe = method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS");
        if (!safe && mode == Mode.LOCAL && ex.getRequestHeaders().getFirst(CLIENT_HEADER) == null) {
            throw new AuthException("Requests that change data must come from the DataBridge app (" + CLIENT_HEADER + " header)", true);
        }
        if (access.open()) {
            current(ex);
            return;
        }
        User u = current(ex);
        if (u == null) throw new AuthException("Sign in to continue", false);
        if (u.mustChangePassword() && !access.beforePasswordChange()) {
            throw new AuthException("Change your temporary password before continuing", true);
        }
        if (!access.roles().isEmpty() && access.roles().stream().noneMatch(u::has)) {
            throw new AuthException("Your roles " + u.roles() + " do not allow this; it needs one of " + access.roles(), true);
        }
    }

    /** The caller, or null. */
    User current(HttpServerExchange ex) {
        User u = ex.getAttachment(USER);
        if (u != null) return u;
        if (mode == Mode.NONE) {
            String h = ex.getRequestHeaders().getFirst(Request.ACTOR_HEADER);
            String name = h == null || h.isBlank() ? "local" : h.trim();
            u = new User("local", name, name, null, EnumSet.allOf(Role.class), true, false, 0, null, null, Instant.EPOCH, "system");
        } else {
            Cookie c = ex.getRequestCookie(COOKIE);
            u = c == null ? null : users.authenticate(c.getValue()).orElse(null);
        }
        if (u != null) ex.putAttachment(USER, u);
        return u;
    }

    String token(HttpServerExchange ex) {
        Cookie c = ex.getRequestCookie(COOKIE);
        return c == null ? null : c.getValue();
    }

    void setSession(HttpServerExchange ex, String token, Instant expires) {
        ex.setResponseCookie(cookie(token, Duration.between(Instant.now(), expires)));
    }

    void clearSession(HttpServerExchange ex) {
        ex.setResponseCookie(cookie("", Duration.ZERO));
    }

    private Cookie cookie(String value, Duration maxAge) {
        CookieImpl c = new CookieImpl(COOKIE, value);
        c.setPath("/");
        c.setHttpOnly(true);
        c.setSameSiteMode("Strict");
        c.setSecure(secureCookie);
        c.setMaxAge((int) Math.max(0, maxAge.toSeconds()));
        return c;
    }

    public static Set<Role> allRoles() {
        return EnumSet.allOf(Role.class);
    }
}
