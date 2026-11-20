# Deploying DataBridge

DataBridge is one Java process (Undertow, no framework) that also serves the built UI, plus a
database. Client files never touch the database: they live in short-lived workspaces on local disk
and are destroyed after processing (ADR 0001).

## Option A: Docker (recommended)

Check out both repositories side by side:

```
DataBridge-Java/
DataBridge-FrontEnd/
```

```bash
cd DataBridge-Java
cat > .env <<'ENV'
DATABRIDGE_DOMAIN=databridge.example.co.ke
DATABRIDGE_ADMIN_PASSWORD=choose-a-long-first-password-1
DATABRIDGE_BC_CLIENT_SECRET=
ENV
docker compose up -d --build
```

Caddy terminates HTTPS (automatic certificates for `DATABRIDGE_DOMAIN`) and forwards to DataBridge;
`DATABRIDGE_SECURE_COOKIE=true` is already set. Data lives in the `databridge-data` volume; client
workspaces in a tmpfs that never reaches disk. Sign in as `admin` with the first password, change it,
then add users under **Users**.

Image only: `docker build --build-context ui=../DataBridge-FrontEnd/data-dashboard -t databridge .`

## Option B: plain Java

```bash
# 1. UI (DataBridge-FrontEnd/data-dashboard)
npm ci && node node_modules/vite/bin/vite.js build          # -> dist/

# 2. Server (DataBridge-Java)
mvn -B verify
export DATABRIDGE_UI_DIR=/path/to/DataBridge-FrontEnd/data-dashboard/dist
export DATABRIDGE_HOST=0.0.0.0             # behind a TLS reverse proxy only
export DATABRIDGE_SECURE_COOKIE=true
java -jar databridge-app/target/databridge-app.jar
```

Put a reverse proxy with TLS (IIS with ARR, nginx, Caddy) in front. DataBridge itself speaks plain
HTTP and must not be exposed directly.

## Database

| Database | `DATABRIDGE_DB_URL` | Notes |
| --- | --- | --- |
| H2 file (default) | `jdbc:h2:file:/data/databridge;MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;AUTO_SERVER=TRUE` | Single server; back up the folder |
| SQL Server | `jdbc:sqlserver://host:1433;databaseName=DataBridge;encrypt=true` | Driver included; set `DATABRIDGE_DB_USER` / `DATABRIDGE_DB_PASSWORD` |
| PostgreSQL, MySQL | | Planned: per-database script folders (ADR 0004); not yet shipped |

Tables are created and upgraded on every start (`V1`-`V7` scripts, checksummed).

## Settings

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `DATABRIDGE_HOST` / `DATABRIDGE_PORT` | `127.0.0.1` / `8085` | Listen address |
| `DATABRIDGE_DB_URL`, `_DB_USER`, `_DB_PASSWORD` | H2 file in `~/databridge-data` | Database |
| `DATABRIDGE_UI_DIR` | empty (API only) | Built UI to serve |
| `DATABRIDGE_AUTH` | `local` | `none` = no sign-in, development only, loopback only |
| `DATABRIDGE_SECURE_COOKIE` | `false` | `true` behind HTTPS (also sends HSTS) |
| `DATABRIDGE_SESSION_IDLE_MINUTES` / `_ABSOLUTE_HOURS` | `120` / `12` | Session limits |
| `DATABRIDGE_ADMIN_PASSWORD` | empty | First start only; empty = generated and printed once |
| `DATABRIDGE_BC_CLIENT_SECRET` | not set | Business Central app secret (name configurable in the Integration Setup) |
| `DATABRIDGE_SWEEP_MINUTES` | `15` | How often expired workspaces and sessions are swept |

Workspace folder, retention (purge on completion or keep for N hours), secure overwrite, finding
value masking and upload limit are setups in the app (**Setup > DataBridge and retention**).

## Security checklist

- HTTPS in front, `DATABRIDGE_SECURE_COOKIE=true`.
- Change the first admin password at once (enforced); give people only the roles they need.
- Keep `DATABRIDGE_AUTH=local` (the server refuses `none` on a non-local address).
- Business Central: an Entra app with read-only API permissions; secret only in the environment.
- Back up the database; it holds setups, users (password hashes), metadata, findings (masked),
  reconciliation totals and the audit trail, never client files.
- Every response carries a Content Security Policy, `X-Frame-Options: DENY`, `nosniff` and
  `Referrer-Policy: no-referrer`.

## Health

`GET /health` (no sign-in) answers 200 with the database status; the Docker image uses it as its
health check.
