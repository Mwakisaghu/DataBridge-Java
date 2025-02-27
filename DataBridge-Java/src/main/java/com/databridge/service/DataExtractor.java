package com.databridge.service;

import com.databridge.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class DataExtractor {
    private static final Logger logger = LoggerFactory.getLogger(DataExtractor.class);
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10); // Thread pool for parallel execution

    public static List<Map<String, Object>> fetchData(String tableName) {
        logger.info("Checking table name: '{}'", tableName); // Debugging Log
        
        /* if (!isValidTableName(tableName)) {
            logger.error("Invalid table name: {}", tableName);
            return Collections.emptyList();
        } */
        
        String query = "SELECT * FROM [" + tableName.replace("[", "").replace("]", "") + "]";
        logger.info("Executing query: {}", query); // Log the actual query
        
        try (Connection conn = DatabaseConfig.getSourceConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
    
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
    
            List<Map<String, Object>> dataList = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnName(i), rs.getObject(i));
                }
                dataList.add(row);
            }
            logger.info("Successfully fetched {} rows from table: {}", dataList.size(), tableName);
            return dataList;
        } catch (SQLException e) {
            logger.error("Error fetching data from table '{}': {}", tableName, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    

    public static Map<String, List<Map<String, Object>>> fetchMultipleTables(List<String> tableNames) {
        Map<String, List<Map<String, Object>>> result = new ConcurrentHashMap<>();
        List<Future<Void>> futures = new ArrayList<>();

        for (String tableName : tableNames) {
            futures.add(executorService.submit(() -> {
                List<Map<String, Object>> data = fetchData(tableName);
                result.put(tableName, data);
                return null;
            }));
        }

        for (Future<Void> future : futures) {
            try {
                future.get(); // Wait for all tasks to complete
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error executing parallel data extraction: {}", e.getMessage(), e);
            }
        }

        return result;
    }

    /* private static boolean isValidTableName(String tableName) {
        return tableName.matches("^[\\[\\]a-zA-Z0-9_$-]+$");
    } */        
}
