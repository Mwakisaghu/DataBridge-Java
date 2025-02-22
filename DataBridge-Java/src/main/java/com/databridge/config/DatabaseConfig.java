package com.databridge.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConfig {
    private static final String SOURCE_DB_URL = "";
    private static final String TARGET_DB_URL = "";

    private static final String SOURCE_DB_USER = "";
    private static final String SOURCE_DB_PASSWORD = "";

    private static final String TARGET_DB_USER = "";
    private static final String TARGET_DB_PASSWORD = "";

    public static Connection getSourceConnection() throws SQLException {
        return DriverManager.getConnection(SOURCE_DB_URL, SOURCE_DB_USER, SOURCE_DB_PASSWORD);
    }

    public static Connection getTargetConnection() throws SQLException {
        return DriverManager.getConnection(TARGET_DB_URL, TARGET_DB_USER, TARGET_DB_PASSWORD);
    }
}
