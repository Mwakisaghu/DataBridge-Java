package com.databridge.service;

import java.sql.*;
import java.util.*;

import com.databridge.config.DatabaseConfig;

public class DataExtractor {
    public static List<Map<String, Object>> fetchData(String tableName) throws SQLException {
        List<Map<String, Object>> dataList = new ArrayList<>();
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
        }
        return dataList;
    }
}
