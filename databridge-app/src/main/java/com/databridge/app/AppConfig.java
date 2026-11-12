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
                        int sweepIntervalMinutes) {

    static final Map<String, String> ENV_KEYS = Map.of(
            "DATABRIDGE_HOST", "server.host",
            "DATABRIDGE_PORT", "server.port",
            "DATABRIDGE_DB_URL", "db.url",
            "DATABRIDGE_DB_USER", "db.user",
            "DATABRIDGE_DB_PASSWORD", "db.password",
            "DATABRIDGE_SWEEP_MINUTES", "retention.sweepIntervalMinutes");

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
                integer(p, "retention.sweepIntervalMinutes", 15));
        if (c.port() < 0 || c.port() > 65535) throw new IllegalArgumentException("server.port must be 0-65535");
        if (c.sweepIntervalMinutes() < 1) throw new IllegalArgumentException("retention.sweepIntervalMinutes must be at least 1");
        return c;
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
