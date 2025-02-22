package com.databridge;

import java.sql.Connection;
import java.sql.SQLException;

import com.databridge.config.DatabaseConfig;

public class DBTest {
    public static void main(String[] args) {
        try (Connection conn = DatabaseConfig.connect()) {
            System.out.println("Database connected successfully...");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}

