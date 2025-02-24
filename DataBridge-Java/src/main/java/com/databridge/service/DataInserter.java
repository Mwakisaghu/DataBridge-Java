package com.databridge.service;

import com.databridge.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class DataInserter {
    private static final Logger logger = LoggerFactory.getLogger(DataInserter.class);

    public static void insertData(String tableName, List<Map<String, Object>> dataList) {
        if (dataList.isEmpty()) {
            logger.warn("No data to insert for table: {}", tableName);
            return;
        }

        // Validate table name to prevent SQL injection
        if (!isValidTableName(tableName)) {
            logger.error("Invalid table name: {}", tableName);
            return;
        }

        try (Connection conn = DatabaseConfig.getTargetConnection()) {
            conn.setAutoCommit(false); // Enable transaction

            // Extract column names from the first row
            Map<String, Object> firstRow = dataList.get(0);
            String columnNames = String.join(", ", firstRow.keySet());
            String placeholders = String.join(", ", Collections.nCopies(firstRow.size(), "?"));
            String sql = "INSERT INTO " + tableName + " (" + columnNames + ") VALUES (" + placeholders + ")";

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (Map<String, Object> row : dataList) {
                    int index = 1;
                    for (Object value : row.values()) {
                        pstmt.setObject(index++, value);
                    }
                    pstmt.addBatch();
                }
                // Execute batch insert
                pstmt.executeBatch(); 

                // Commit transaction
                conn.commit(); 
                logger.info("Successfully inserted {} rows into table: {}", dataList.size(), tableName);
            } catch (SQLException e) {
                // Rollback transaction on error
                conn.rollback(); 
                logger.error("Error inserting data into table '{}': {}", tableName, e.getMessage(), e);
            }

        } catch (SQLException e) {
            logger.error("Database connection error for table '{}': {}", tableName, e.getMessage(), e);
        }
    }

    private static boolean isValidTableName(String tableName) {
        return tableName.matches("^[a-zA-Z0-9_]+$");
    }
}
