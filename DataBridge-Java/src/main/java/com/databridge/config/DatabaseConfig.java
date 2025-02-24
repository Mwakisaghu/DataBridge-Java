package com.databridge.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class DatabaseConfig {
    private static final String SOURCE_DB_URL = System.getenv("SOURCE_DB_URL");
    private static final String TARGET_DB_URL = System.getenv("TARGET_DB_URL");

    private static final String SOURCE_DB_USER = System.getenv("SOURCE_DB_USER");
    private static final String SOURCE_DB_PASSWORD = System.getenv("SOURCE_DB_PASSWORD");

    private static final String TARGET_DB_USER = System.getenv("TARGET_DB_USER");
    private static final String TARGET_DB_PASSWORD = System.getenv("TARGET_DB_PASSWORD");

    public static Connection getSourceConnection() {
        return createConnection(SOURCE_DB_URL, SOURCE_DB_USER, SOURCE_DB_PASSWORD);
    }

    public static Connection getTargetConnection() {
        return createConnection(TARGET_DB_URL, TARGET_DB_USER, TARGET_DB_PASSWORD);
    }

    private static Connection createConnection(String url, String user, String password) {
        try {
            Properties props = new Properties();
            props.setProperty("user", user);
            props.setProperty("password", password);
            return DriverManager.getConnection(url, props);
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());
            return null;
        }
    }
}
