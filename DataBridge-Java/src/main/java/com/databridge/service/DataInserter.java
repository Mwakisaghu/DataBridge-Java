package com.databridge.service;

import java.sql.*;
import java.util.*;

import com.databridge.config.DatabaseConfig;

public class DataInserter {
    public static void insertData(String tableName, List<Map<String, Object>> dataList) throws SQLException {
        if (dataList.isEmpty()) return;

        try (Connection conn = DatabaseConfig.getTargetConnection()) {
            for (Map<String, Object> row : dataList) {
                StringBuilder columns = new StringBuilder();
                StringBuilder values = new StringBuilder();
                List<Object> parameters = new ArrayList<>();

                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    columns.append(entry.getKey()).append(",");
                    values.append("?,");
                    parameters.add(entry.getValue());
                }

                String sql = "INSERT INTO " + tableName + " (" +
                        columns.substring(0, columns.length() - 1) + ") VALUES (" +
                        values.substring(0, values.length() - 1) + ")";

                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    for (int i = 0; i < parameters.size(); i++) {
                        pstmt.setObject(i + 1, parameters.get(i));
                    }
                    pstmt.executeUpdate();
                }
            }
        }
    }
}
