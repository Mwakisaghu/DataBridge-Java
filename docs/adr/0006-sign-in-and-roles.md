# ADR 0006: Sign-in and roles

Status: accepted, November 2026

## Context

Phase 1 trusted a name in the `X-DataBridge-User` header and listened on 127.0.0.1 only. Serving a
team needs real identities: waivers are maker-checker, the audit trail must name real people, and
finance approvals (Phase 2) and run sign-off (Phase 5) need roles. The project stays on plain Undertow
(no Spring Security), and Microsoft Entra ID is wanted later but needs tenant details.

## Decision

- **Local accounts first**, behind a small `UserService` in the core so Entra ID (OIDC) can be added as
  a second way to establish the same session later, without touching routes.
- **Passwords**: PBKDF2-HMAC-SHA256, 210,000 iterations, 16-byte salt, stored as one self-describing
  string (`pbkdf2-sha256$iterations$salt$hash`) so the cost can rise later. At least 12 characters with
  letters and a digit or symbol. Five wrong passwords lock the account for 15 minutes. Unknown user
  and wrong password give the same message and the same work.
- **Sessions**: a random 256-bit token in an `HttpOnly`, `SameSite=Strict` cookie (`DB_SESSION`,
  `Secure` when `auth.secureCookie=true`). Only the token's SHA-256 is stored, so a database copy
  cannot be used to sign in. Sessions end after 2 hours idle or 12 hours in all; disabling a user or
  resetting their password ends theirs at once.
- **Cross-site requests**: every non-GET call must carry `X-DataBridge-Client`. Browsers cannot add
  a custom header to a cross-site form post without a CORS preflight, which DataBridge never grants.
- **Roles** ADMIN, CONSULTANT, REVIEWER, FINANCE, VIEWER; ADMIN holds all. Every route declares its
  access (`Access.OPEN`, `ANY`, `SELF`, or roles) where it is registered, so a review of
  `ApiRoutes`/`ValidationRoutes`/`AuthRoutes` shows the whole policy. Maker-checker still compares
  people, so an admin cannot approve their own waiver.
- **First start**: with no users, `admin` is created with a temporary password printed once (or the
  one in `auth.initialAdminPassword`). Temporary passwords must be changed before anything else.
- **Development mode** `auth.mode=none` keeps the old behaviour (header name, every role) and is
  refused unless the server listens on a local address.

## Consequences

- Scripts sign in and keep the cookie (see README).
- User accounts and session hashes are DataBridge's own data, not client data; the no-retention rule
  (ADR 0001) is unaffected.
- Entra ID: add an OIDC login route that maps the token's groups to roles and then issues the same
  session. Needs the tenant ID, an app registration (client ID/secret, redirect URI) and the group to
  role mapping from the client.
