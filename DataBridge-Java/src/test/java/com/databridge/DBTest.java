package com.databridge;

import java.sql.Connection;
import java.sql.SQLException;
import com.databridge.config.DatabaseConfig;

public class DBTest {
    public static void main(String[] args) {
        if (testSourceDatabase()) {
            System.out.println("SUCCESS : Source Database connected successfully...");
        } else {
            System.out.println("ERROR : Failed to connect to Source Database.");
        }

        if (testTargetDatabase()) {
            System.out.println("SUCCESS : Target Database connected successfully...");
        } else {
            System.out.println("ERROR: Failed to connect to Target Database.");
        }
    }

    private static boolean testSourceDatabase() {
        try (Connection conn = DatabaseConfig.getSourceConnection()) {
            return conn != null;
        } catch (SQLException e) {
            System.err.println("ERROR: Source Database connection failed!");
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testTargetDatabase() {
        try (Connection conn = DatabaseConfig.getTargetConnection()) {
            return conn != null;
        } catch (SQLException e) {
            System.err.println("ERROR: Target Database connection failed!");
            e.printStackTrace();
            return false;
        }
    }
}
