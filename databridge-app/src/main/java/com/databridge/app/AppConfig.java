package com.databridge.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Infrastructure settings only: where to listen and which database to use. Business
 * settings (retention, limits, templates) are setups in the database.
 *
 * <p>Read in rising priority from: {@code /databridge-app.properties} on the classpath,
 * a file given with {@code --config <file>}, then environment variables.
 */
public record AppConfig(String host, int port, String dbUrl, String dbUser, String dbPassword,
                        int sweepIntervalMinutes, String authMode, boolean secureCookie,
                        int sessionIdleMinutes, int sessionAbsoluteHours, String adminPassword, String uiDir) {

    static final Map<String, String> ENV_KEYS = Map.ofEntries(
            Map.entry("DATABRIDGE_HOST", "server.host"),
            Map.entry("DATABRIDGE_PORT", "server.port"),
            Map.entry("DATABRIDGE_DB_URL", "db.url"),
            Map.entry("DATABRIDGE_DB_USER", "db.user"),
            Map.entry("DATABRIDGE_DB_PASSWORD", "db.password"),
            Map.entry("DATABRIDGE_SWEEP_MINUTES", "retention.sweepIntervalMinutes"),
            Map.entry("DATABRIDGE_AUTH", "auth.mode"),
            Map.entry("DATABRIDGE_SECURE_COOKIE", "auth.secureCookie"),
            Map.entry("DATABRIDGE_SESSION_IDLE_MINUTES", "auth.sessionIdleMinutes"),
            Map.entry("DATABRIDGE_SESSION_ABSOLUTE_HOURS", "auth.sessionAbsoluteHours"),
            Map.entry("DATABRIDGE_ADMIN_PASSWORD", "auth.initialAdminPassword"),
            Map.entry("DATABRIDGE_UI_DIR", "server.uiDir"));

    public static AppConfig load(String[] args, Map<String, String> env) {
        Properties p = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream("/databridge-app.properties")) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read built-in databridge-app.properties", e);
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config")) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("--config needs a file");
                Path file = Path.of(args[++i]);
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(r);
                } catch (IOException e) {
                    throw new IllegalArgumentException("Cannot read config file " + file + ": " + e.getMessage(), e);
                }
            } else {
                throw new IllegalArgumentException("Unknown argument " + args[i] + " (use --config <file>)");
            }
        }
        ENV_KEYS.forEach((env1, key) -> {
            String v = env.get(env1);
            if (v != null && !v.isBlank()) p.setProperty(key, v);
        });
        AppConfig c = new AppConfig(
                p.getProperty("server.host", "127.0.0.1"),
                integer(p, "server.port", 8085),
                required(p, "db.url"),
                p.getProperty("db.user", ""),
                p.getProperty("db.password", ""),
                integer(p, "retention.sweepIntervalMinutes", 15),
                p.getProperty("auth.mode", "local").trim().toLowerCase(java.util.Locale.ROOT),
                Boolean.parseBoolean(p.getProperty("auth.secureCookie", "false").trim()),
                integer(p, "auth.sessionIdleMinutes", 120),
                integer(p, "auth.sessionAbsoluteHours", 12),
                p.getProperty("auth.initialAdminPassword", ""),
                p.getProperty("server.uiDir", "").trim());
        if (c.port() < 0 || c.port() > 65535) throw new IllegalArgumentException("server.port must be 0-65535");
        if (c.sweepIntervalMinutes() < 1) throw new IllegalArgumentException("retention.sweepIntervalMinutes must be at least 1");
        if (!c.authMode().equals("local") && !c.authMode().equals("none")) {
            throw new IllegalArgumentException("auth.mode must be local or none, got '" + c.authMode() + "'");
        }
        if (c.authMode().equals("none") && !c.loopback()) {
            throw new IllegalArgumentException("auth.mode=none (no sign-in) is only allowed when server.host is 127.0.0.1 or localhost");
        }
        if (c.sessionIdleMinutes() < 1 || c.sessionAbsoluteHours() < 1) {
            throw new IllegalArgumentException("auth.sessionIdleMinutes and auth.sessionAbsoluteHours must be at least 1");
        }
        return c;
    }

    /** True when the server listens on this machine only. */
    public boolean loopback() {
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
    }

    /** Never print the initial admin password. */
    @Override
    public String toString() {
        return "AppConfig[host=" + host + ", port=" + port + ", db=" + safeDbUrl() + ", auth=" + authMode + "]";
    }

    /** Database URL with any password parameter hidden, for logs. */
    public String safeDbUrl() {
        return dbUrl.replaceAll("(?i)(password=)[^;]*", "$1***");
    }

    private static String required(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(key + " is required");
        return v.trim();
    }

    private static int integer(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, got '" + v + "'");
        }
    }
}
