package com.databridge.service;

import com.databridge.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class DataInserter {
    private static final Logger logger = LoggerFactory.getLogger(DataInserter.class);
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10);

    public static void insertData(String tableName, List<Map<String, Object>> dataList) {
        if (dataList.isEmpty()) {
            logger.warn("No data to insert for table: {}", tableName);
            return;
        }
        if (!isValidTableName(tableName)) {
            logger.error("Invalid table name: {}", tableName);
            return;
        }

        try (Connection conn = DatabaseConfig.getTargetConnection()) {
            conn.setAutoCommit(false);
            String escapedTableName = "[" + tableName + "]";
            Map<String, Object> firstRow = dataList.get(0);
            List<String> escapedColumnNames = new ArrayList<>();
            List<String> placeholdersList = new ArrayList<>();

            // Iterate through the columns and prepare for insertion
            for (String columnName : firstRow.keySet()) {
                if (!"timestamp".equalsIgnoreCase(columnName)) {
                    escapedColumnNames.add("[" + columnName + "]");
                    placeholdersList.add("?");
                }
            }

            // Combine First Name, Middle Name, and Other Names into Name
            for (Map<String, Object> row : dataList) {
                String firstName = (String) row.get("First Name");
                String middleName = (String) row.get("Middle Name");
                String otherNames = (String) row.get("Other Names");

                // Construct the Name field by combining the names (add null checks)
                String fullName = (firstName == null ? "" : firstName) + " " +
                                  (middleName == null ? "" : middleName) + " " +
                                  (otherNames == null ? "" : otherNames);

                row.put("Name", fullName.trim());  // Store combined name in the "Name" field
            }

            String columnNames = String.join(", ", escapedColumnNames);
            String placeholders = String.join(", ", placeholdersList);
            String sql = "INSERT INTO " + escapedTableName + " (" + columnNames + ") VALUES (" + placeholders + ")";

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (Map<String, Object> row : dataList) {
                    int index = 1;
                    for (String columnName : row.keySet()) {
                        if ("timestamp".equalsIgnoreCase(columnName)) continue;
                        Object value = row.get(columnName);
                        int maxLength = getColumnMaxLength(tableName, columnName);

                        if (value instanceof String) {
                            String stringValue = (String) value;
                            if (maxLength > 0 && stringValue.length() > maxLength) {
                                logger.warn("Truncating column '{}' from '{}' to '{}' (Max Length: {})", 
                                    columnName, stringValue, stringValue.substring(0, maxLength), maxLength);
                                stringValue = stringValue.substring(0, maxLength);
                            }
                            pstmt.setString(index++, stringValue);
                        } else if (value instanceof byte[]) {
                            pstmt.setBytes(index++, (byte[]) value);
                        } else {
                            pstmt.setObject(index++, value);
                        }
                    }
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
                conn.commit();
                logger.info("Successfully inserted {} rows into table: {}", dataList.size(), tableName);
            } catch (SQLException e) {
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
            futures.add(executorService.submit(() -> {
                insertData(entry.getKey(), entry.getValue());
                return null;
            }));
        }
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error executing parallel data insertion: {}", e.getMessage(), e);
            }
        }
    }

    private static int getColumnMaxLength(String tableName, String columnName) {
        String sql = "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (Connection conn = DatabaseConfig.getTargetConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            stmt.setString(2, columnName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("CHARACTER_MAXIMUM_LENGTH");
            }
        } catch (SQLException e) {
            logger.error("Error fetching column length for {}.{}: {}", tableName, columnName, e.getMessage());
        }
        return -1;
    }

    // Method to validate the table name (optional)
    private static boolean isValidTableName(String tableName) {
        // Allow table names with alphanumeric characters, spaces, _, -, and $
        return tableName.matches("^[a-zA-Z0-9_\\-\\$ ]+$");
    }
}
