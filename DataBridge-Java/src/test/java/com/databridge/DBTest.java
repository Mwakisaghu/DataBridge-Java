package com.databridge;

import java.sql.Connection;
import java.sql.SQLException;

import com.databridge.config.DatabaseConfig;

public class DBTest {
    public static void main(String[] args) {
        testSourceDatabase();
        testTargetDatabase();
    }

    private static void testSourceDatabase() {
        try (Connection conn = DatabaseConfig.getSourceConnection()) {
            System.out.println("Source Database connected successfully...");
        } catch (SQLException e) {
            System.err.println("Failed to connect to Source Database:");
            e.printStackTrace();
        }
    }

    private static void testTargetDatabase() {
        try (Connection conn = DatabaseConfig.getTargetConnection()) {
            System.out.println("Target Database connected successfully...");
        } catch (SQLException e) {
            System.err.println("Failed to connect to Target Database:");
            e.printStackTrace();
        }
    }
}