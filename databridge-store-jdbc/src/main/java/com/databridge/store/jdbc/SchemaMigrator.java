package com.databridge.store.jdbc;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the versioned SQL scripts for the connected database, in order. Scripts live in
 * {@code /db/migration/<database>/} with an {@code index.txt}, where database is
 * {@code sqlserver}, {@code h2}, {@code postgresql} or {@code mysql}; SQL Server and H2 (in
 * MSSQLServer mode) share the default scripts in {@code /db/migration/}. Each script runs in
 * its own transaction and is recorded in {@code databridge_schema_history}.
 * A script that was changed after being applied is refused (checksum mismatch), so a
 * database can never silently drift from the code. Line endings are normalised before
 * hashing, so a Windows checkout with CRLF gives the same checksum.
 */
public final class SchemaMigrator {

    /** A script applied by this run. */
    public record Applied(int version, String script) { }

    static final String HISTORY = "databridge_schema_history";
    private static final Pattern NAME = Pattern.compile("^V(\\d+)__[A-Za-z0-9_]+\\.sql$");

    private static final String ROOT = "/db/migration";

    private final DataSource dataSource;

    public SchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Maps a JDBC product name to its script folder name. */
    static String vendor(String productName) {
        String p = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (p.contains("sql server")) return "sqlserver";
        if (p.equals("h2")) return "h2";
        if (p.contains("postgres")) return "postgresql";
        if (p.contains("mysql") || p.contains("mariadb")) return "mysql";
        return p.replaceAll("[^a-z0-9]", "");
    }

    /** The script folder for a database: its own folder if present, else the shared default. */
    static String folderFor(String vendor) {
        String own = ROOT + "/" + vendor;
        if (SchemaMigrator.class.getResource(own + "/index.txt") != null) return own;
        if (vendor.equals("sqlserver") || vendor.equals("h2")) return ROOT;
        throw new MigrationException("No database scripts for '" + vendor + "' yet. Add " + own
                + "/index.txt and its V<n>__*.sql scripts (see docs/adr/0004).");
    }

    public List<Applied> migrate() {
        List<Applied> applied = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            Map<Integer, String[]> scripts = scripts(folderFor(vendor(c.getMetaData().getDatabaseProductName())));
            ensureHistory(c);
            Map<Integer, String> done = history(c);
            for (Map.Entry<Integer, String> d : done.entrySet()) {
                String[] s = scripts.get(d.getKey());
                if (s == null) {
                    throw new MigrationException("Database has V" + d.getKey() + " applied, but this build has no such script. "
                            + "Is the application older than the database?");
                }
                if (!s[1].equals(d.getValue())) {
                    throw new MigrationException(s[0] + " has changed since it was applied. Never edit an applied script; "
                            + "add a new version instead.");
                }
            }
            for (Map.Entry<Integer, String[]> s : scripts.entrySet()) {
                if (done.containsKey(s.getKey())) continue;
                apply(c, s.getKey(), s.getValue()[0], s.getValue()[1], s.getValue()[2]);
                applied.add(new Applied(s.getKey(), s.getValue()[0]));
            }
        } catch (SQLException e) {
            throw new MigrationException("Schema migration failed: " + e.getMessage(), e);
        }
        return applied;
    }

    private void apply(Connection c, int version, String name, String checksum, String sql) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            for (String stmt : statements(sql)) st.execute(stmt);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + HISTORY + " (version, script, checksum, applied_at) VALUES (?, ?, ?, ?)")) {
                ps.setInt(1, version);
                ps.setString(2, name);
                ps.setString(3, checksum);
                ps.setTimestamp(4, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw new MigrationException(name + " failed: " + e.getMessage(), e);
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static void ensureHistory(Connection c) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        for (String name : new String[] {HISTORY, HISTORY.toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = md.getTables(null, null, name, new String[] {"TABLE"})) {
                if (rs.next()) return;
            }
        }
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE " + HISTORY + " ("
                    + "version INT NOT NULL, "
                    + "script NVARCHAR(200) NOT NULL, "
                    + "checksum CHAR(64) NOT NULL, "
                    + "applied_at DATETIME2 NOT NULL, "
                    + "CONSTRAINT pk_" + HISTORY + " PRIMARY KEY (version))");
        }
    }

    private static Map<Integer, String> history(Connection c) throws SQLException {
        Map<Integer, String> done = new LinkedHashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version, checksum FROM " + HISTORY + " ORDER BY version")) {
            while (rs.next()) done.put(rs.getInt(1), rs.getString(2));
        }
        return done;
    }

    /** version -> {script name, checksum, normalised text}, in version order. */
    private static Map<Integer, String[]> scripts(String base) {
        Map<Integer, String[]> out = new java.util.TreeMap<>();
        for (String name : index(base)) {
            Matcher m = NAME.matcher(name);
            if (!m.matches()) throw new MigrationException("Bad migration script name: " + name);
            int v = Integer.parseInt(m.group(1));
            String sql = read(base + "/" + name).replace("\r\n", "\n").replace('\r', '\n');
            if (out.put(v, new String[] {name, sha256(sql), sql}) != null) {
                throw new MigrationException("Two scripts claim version " + v);
            }
        }
        return out;
    }

    private static List<String> index(String base) {
        String text = read(base + "/index.txt");
        List<String> names = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String l = line.trim();
            if (!l.isEmpty() && !l.startsWith("#")) names.add(l);
        }
        return names;
    }

    private static String read(String resource) {
        try (InputStream in = SchemaMigrator.class.getResourceAsStream(resource)) {
            if (in == null) throw new MigrationException("Missing migration resource " + resource);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
                return sb.toString();
            }
        } catch (IOException e) {
            throw new MigrationException("Cannot read " + resource, e);
        }
    }

    /**
     * Splits a script into statements on ';' or on a line holding only GO, after removing
     * '--' comments. Scripts must not put ';' or '--' inside string literals.
     */
    static List<String> statements(String script) {
        StringBuilder sb = new StringBuilder();
        for (String line : script.split("\n")) {
            int dash = line.indexOf("--");
            String l = dash >= 0 ? line.substring(0, dash) : line;
            sb.append(l.trim().equalsIgnoreCase("GO") ? ";" : l).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String s : sb.toString().split(";")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
