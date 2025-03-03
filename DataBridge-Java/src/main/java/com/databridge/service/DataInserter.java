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

    // Method to insert data into the specified table
    public static void insertData(String tableName, List<Map<String, Object>> dataList) {
        if (dataList.isEmpty()) {
            logger.warn("No data to insert for table: {}", tableName);
            return;
        }

        // Validate table name to prevent SQL injection (optional)
        if (!isValidTableName(tableName)) {
            logger.error("Invalid table name: {}", tableName);
            return;
        }

        try (Connection conn = DatabaseConfig.getTargetConnection()) {
            conn.setAutoCommit(false); // Enable transaction

            // Escape the table name properly
            String escapedTableName = "[" + tableName + "]"; // Wrap the table name in square brackets

            // Extract column names from the first row and escape them
            Map<String, Object> firstRow = dataList.get(0);
            List<String> escapedColumnNames = new ArrayList<>();
            List<String> placeholdersList = new ArrayList<>();

            // Loop through the columns of the first row
            for (Map.Entry<String, Object> entry : firstRow.entrySet()) {
                String columnName = entry.getKey();
                if ("timestamp".equalsIgnoreCase(columnName)) {
                    continue; // Exclude the timestamp column
                }
                escapedColumnNames.add("[" + columnName + "]"); // Escape column name
                placeholdersList.add("?"); // Add placeholder for value
            }

            String columnNames = String.join(", ", escapedColumnNames);
            String placeholders = String.join(", ", placeholdersList);

            // Construct the SQL query
            String sql = "INSERT INTO " + escapedTableName + " (" + columnNames + ") VALUES (" + placeholders + ")";

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                // Iterate through each row of data
                for (Map<String, Object> row : dataList) {
                    int index = 1;
                    // Add values for each row, skipping the timestamp column
                    for (String columnName : row.keySet()) {
                        if ("timestamp".equalsIgnoreCase(columnName)) {
                            continue;
                        }

                        Object value = row.get(columnName);

                        // Log string values to debug any issues
                        if (value instanceof String) {
                            String stringValue = (String) value;
                            logger.debug("Inserting value for column '{}' with value: {}", columnName, stringValue);
                            pstmt.setString(index++, stringValue); // Set the string value
                        } else if (value instanceof byte[]) {
                            // Handle binary data (BLOB, VARBINARY, etc.)
                            pstmt.setBytes(index++, (byte[]) value);
                        } else {
                            // For non-String types, just set the value
                            pstmt.setObject(index++, value);
                        }
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

    // Method to insert data into multiple tables concurrently
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

    // Method to validate the table name (optional)
    private static boolean isValidTableName(String tableName) {
        // Allow table names with alphanumeric characters, spaces, _, -, and $
        return tableName.matches("^[a-zA-Z0-9_\\-\\$ ]+$");
    }
}
