package com.databridge.service;

import com.databridge.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class DataInserter {
    private static final Logger logger = LoggerFactory.getLogger(DataInserter.class);
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10); // Thread pool for parallel execution

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

    public static void insertMultipleTables(Map<String, List<Map<String, Object>>> tableData) {
        List<Future<Void>> futures = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, Object>>> entry : tableData.entrySet()) {
            String tableName = entry.getKey();
            List<Map<String, Object>> dataList = entry.getValue();

            futures.add(executorService.submit(() -> {
                insertData(tableName, dataList);
                return null;
            }));
        }

        for (Future<Void> future : futures) {
            try {
                future.get(); // Wait for all tasks to complete
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error executing parallel data insertion: {}", e.getMessage(), e);
            }
        }
    }

    private static boolean isValidTableName(String tableName) {
        return tableName.matches("^[a-zA-Z0-9_]+$");
    }
}
