package com.databridge.service;

import com.databridge.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class DataExtractor {
    private static final Logger logger = LoggerFactory.getLogger(DataExtractor.class);

    public static List<Map<String, Object>> fetchData(String tableName) {
        List<Map<String, Object>> dataList = new ArrayList<>();

        // Validate table name (to prevent SQL injection)
        if (!isValidTableName(tableName)) {
            logger.error("Invalid table name: {}", tableName);
            return Collections.emptyList();
        }

        String query = "SELECT * FROM " + tableName;

        try (Connection conn = DatabaseConfig.getSourceConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnName(i), rs.getObject(i));
                }
                dataList.add(row);
            }
            logger.info("Successfully fetched {} rows from table: {}", dataList.size(), tableName);
        } catch (SQLException e) {
            logger.error("Error fetching data from table '{}': {}", tableName, e.getMessage(), e);
        }

        return dataList;
    }

    private static boolean isValidTableName(String tableName) {
        return tableName.matches("^[a-zA-Z0-9_]+$");
    }
}
