package com.databridge.service;

import com.databridge.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class DataInserter {
    private static final Logger logger = LoggerFactory.getLogger(DataInserter.class);
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10);  // Thread pool for parallelization

    public static void insertData(String tableName, List<Map<String, Object>> dataList) {
        if (dataList.isEmpty()) {
            logger.warn("No data to insert for table: {}", tableName);
            return;
        }
        if (!isValidTableName(tableName)) {
            logger.error("Invalid table name: {}", tableName);
            return;
        }

        int batchSize = 100;
        List<List<Map<String, Object>>> dataChunks = chunkData(dataList, batchSize);

        // Execute parallel inserts for each chunk
        List<Future<Void>> futures = new ArrayList<>();
        for (List<Map<String, Object>> chunk : dataChunks) {
            futures.add(executorService.submit(() -> {
                insertChunk(tableName, chunk);
                return null;
            }));
        }

        // Wait for all threads to complete
        try {
            for (Future<Void> future : futures) {
                // Ensures that the main thread waits for all inserts to finish
                future.get();  
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error during parallel data insertion", e);
        }

        logger.info("Data insertion completed for table: {}", tableName);
    }

    private static List<List<Map<String, Object>>> chunkData(List<Map<String, Object>> dataList, int batchSize) {
        List<List<Map<String, Object>>> chunks = new ArrayList<>();
        for (int i = 0; i < dataList.size(); i += batchSize) {
            chunks.add(dataList.subList(i, Math.min(i + batchSize, dataList.size())));
        }
        return chunks;
    }

    private static void insertChunk(String tableName, List<Map<String, Object>> chunk) {
        try (Connection conn = DatabaseConfig.getTargetConnection()) {
            conn.setAutoCommit(false);
            String escapedTableName = "[" + tableName + "]";
            Map<String, Object> firstRow = chunk.get(0);
            List<String> escapedColumnNames = new ArrayList<>();
            List<String> placeholdersList = new ArrayList<>();

            Set<String> targetColumns = getTargetTableColumns(tableName);
            Map<String, String> columnTypes = getTargetColumnTypes(tableName);

            for (String columnName : firstRow.keySet()) {
                if (!"timestamp".equalsIgnoreCase(columnName) && targetColumns.contains(columnName)) {
                    escapedColumnNames.add("[" + columnName + "]");
                    placeholdersList.add("?");
                }
            }

            for (String targetColumn : targetColumns) {
                if (!firstRow.containsKey(targetColumn)) {
                    logger.warn("Column '{}' is missing from the source data, setting default value", targetColumn);
                    escapedColumnNames.add("[" + targetColumn + "]");
                    placeholdersList.add("?");
                }
            }

            // Combine First Name, Middle Name, and Other Names into Name
            for (Map<String, Object> row : chunk) {
                String firstName = (String) row.get("First Name");
                String middleName = (String) row.get("Middle Name");
                String otherNames = (String) row.get("Other Names");

                // Construct the Name field by combining the names (add null checks)
                String fullName = (firstName == null ? "" : firstName) + " " +
                                  (middleName == null ? "" : middleName) + " " +
                                  (otherNames == null ? "" : otherNames);

                row.put("Name", fullName.trim());
            }

            String columnNames = String.join(", ", escapedColumnNames);
            String placeholders = String.join(", ", placeholdersList);
            String sql = "INSERT INTO " + escapedTableName + " (" + columnNames + ") VALUES (" + placeholders + ")";
            logger.info("Generated SQL: {}", sql);

            if ("CRONUS International Ltd_$Salary Lines".equalsIgnoreCase(tableName)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET IDENTITY_INSERT " + escapedTableName + " ON");
                }
            }

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (Map<String, Object> row : chunk) {
                    int index = 1;
                    for (String columnName : escapedColumnNames) {
                        String columnKey = columnName.replace("[", "").replace("]", "");
                        String columnType = columnTypes.getOrDefault(columnKey, "UNKNOWN");
                        Object columnValue = row.get(columnKey);

                        // Ensure 'Actual Date Posted' is never NULL
                        if ("Actual Date Posted".equalsIgnoreCase(columnKey) && columnValue == null) {
                            logger.info("Column '{}' is NULL, replacing with current timestamp.", columnKey);
                            columnValue = new Timestamp(System.currentTimeMillis());
                        }

                        // Fetch column max length
                        Integer maxLength = getColumnMaxLength(tableName, columnKey);
                        if (columnValue instanceof String && maxLength != null && maxLength > 0) {
                            String valueStr = (String) columnValue;
                            if (valueStr.length() > maxLength) {
                                logger.warn("Value for column '{}' exceeds max length ({}), truncating.", columnKey, maxLength);
                                columnValue = valueStr.substring(0, maxLength);
                            }
                        }

                        // Handle Image Column Mismatch
                        if ("image".equalsIgnoreCase(columnType)) {
                            if (columnValue instanceof String) {
                                logger.warn("Column '{}' expects 'image', but received a String. Converting to empty binary data.", columnKey);
                                pstmt.setBytes(index++, new byte[0]);
                            } else if (columnValue instanceof byte[]) {
                                pstmt.setBytes(index++, (byte[]) columnValue);
                            } else {
                                logger.warn("Column '{}' expects 'image', but received '{}'. Setting as NULL.", columnKey, columnValue);
                                pstmt.setNull(index++, Types.BINARY);
                            }
                        }
                        // Handle Limit Columns with Default Values
                        else if (isLimitColumn(columnKey) && columnValue == null) {
                            logger.info("'{}' is missing, setting default value (1,000,000).", columnKey);
                            pstmt.setObject(index++, 100000);
                        }
                        else {
                            pstmt.setObject(index++, columnValue);
                        }
                    }
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
                conn.commit();
                logger.info("Successfully inserted {} rows into table: {}", chunk.size(), tableName);
            } catch (SQLException e) {
                conn.rollback();
                logger.error("Error inserting data into table '{}': {}", tableName, e.getMessage(), e);
            } finally {
                if ("CRONUS International Ltd_$Salary Lines".equalsIgnoreCase(tableName)) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("SET IDENTITY_INSERT " + escapedTableName + " OFF");
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Database connection error for table '{}': {}", tableName, e.getMessage(), e);
        }
    }

    private static boolean isLimitColumn(String columnName) {
        return Set.of(
                "ATM Withdrawal Limit", "Mobile Withdrawal Limit", "Mobile Daily Withdrawal Limit",
                "ATM Daily Withdrawals Limit", "Checkoff Amount", "Salary Amount"
        ).contains(columnName);
    }

    private static Set<String> getTargetTableColumns(String tableName) {
        Set<String> columns = new HashSet<>();
        String sql = "SELECT COLUMN_NAME, COLUMNPROPERTY(OBJECT_ID(?), COLUMN_NAME, 'IsIdentity') AS IsIdentity " +
                     "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ?";
        try (Connection conn = DatabaseConfig.getTargetConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            stmt.setString(2, tableName);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                if (rs.getInt("IsIdentity") != 1) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
        } catch (SQLException e) {
            logger.error("Error fetching columns for table '{}': {}", tableName, e.getMessage(), e);
        }
        return columns;
    }

    private static Map<String, String> getTargetColumnTypes(String tableName) {
        Map<String, String> columnTypes = new HashMap<>();
        String sql = "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ?";
        try (Connection conn = DatabaseConfig.getTargetConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tableName);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                columnTypes.put(rs.getString("COLUMN_NAME"), rs.getString("DATA_TYPE"));
            }
        } catch (SQLException e) {
            logger.error("Error fetching column types for table '{}': {}", tableName, e.getMessage(), e);
        }
        return columnTypes;
    }

    private static int getColumnMaxLength(String tableName, String columnName) {
        String sql = "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (Connection conn = DatabaseConfig.getTargetConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
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

    private static boolean isValidTableName(String tableName) {
        return tableName.matches("^[a-zA-Z0-9_\\-\\$ ]+$");
    }
}
