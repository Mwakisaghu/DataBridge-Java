package com.databridge.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.databridge.config.DatabaseConfig;

public class DataUpdater {
    private static final Logger logger = LoggerFactory.getLogger(DataUpdater.class);

    public static String updateMultipleTables(List<String> tables) {
        try (Connection conn = DatabaseConfig.getTargetConnection()) {
            // Start transaction
            conn.setAutoCommit(false); 

            StringJoiner updateSummary = new StringJoiner(", ");

            for (String table : tables) {
                String sql = "UPDATE " + table + " " +
                             "SET " + table + ".name = Members.name, " +
                             "    " + table + ".current_address = Members.current_address " +
                             "FROM " + table + " " +
                             "INNER JOIN Members ON " + table + ".member_no = Members.member_no;";

                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    int affectedRows = pstmt.executeUpdate();
                    updateSummary.add(table + " (" + affectedRows + " rows updated)");
                    logger.info("Updated {} rows in {}", affectedRows, table);
                }
            }

            // Commit transaction if all updates succeed
            conn.commit(); 
            logger.info("All updates applied successfully.");
            return "Updates applied successfully: " + updateSummary.toString();

        } catch (SQLException e) {
            logger.error("Update error: {}", e.getMessage(), e);
            try (Connection conn = DatabaseConfig.getTargetConnection()) {
                // Rollback transaction on error
                conn.rollback(); 
                
                logger.warn("Transaction rolled back due to an error.");
            } catch (SQLException rollbackEx) {
                logger.error("Rollback error: {}", rollbackEx.getMessage(), rollbackEx);
            }
            return "Update failed: " + e.getMessage();
        }
    }
}
