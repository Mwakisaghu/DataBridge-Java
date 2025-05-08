package com.databridge.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.databridge.config.DatabaseConfig;

public class DataUpdater {
    private static final Logger logger = LoggerFactory.getLogger(DataUpdater.class);

    // column mappings for each table
    private static final Map<String, Map<String, String>> tableColumnMappings = new HashMap<>();

    static {
        Map<String, String> savingsColumns = new HashMap<>();
        savingsColumns.put("Name", "M.[Name]");
        savingsColumns.put("Current Address", "M.[Current Address]");
        savingsColumns.put("Home Address", "M.[Home Address]");
        savingsColumns.put("Phone No_", "M.[Phone No_]");
        savingsColumns.put("Employer Code", "M.[Employer Code]");
        savingsColumns.put("Payroll_Staff No_", "M.[Payroll_Staff No_]");
        savingsColumns.put("ID No_", "M.[Primary Identification]");
        savingsColumns.put("Passport No_", "M.[Secondary Identification]");
        savingsColumns.put("Transactional Mobile No", "M.[Transactional Mobile Number]");   
        tableColumnMappings.put("[CRONUS International Ltd_$Savings Accounts$aebc043a-a98e-45f6-8dce-4c8bdabf97fd]", savingsColumns);

        Map<String, String> loansColumns = new HashMap<>();
        loansColumns.put("Member Name", "M.[Name]");
        loansColumns.put("Staff No", "M.[Payroll_Staff No_]");
        loansColumns.put("Employer Code", "M.[Employer Code]");
        loansColumns.put("ID No_", "M.[Primary Identification]");
        tableColumnMappings.put("[CRONUS International Ltd_$Loans$aebc043a-a98e-45f6-8dce-4c8bdabf97fd]", loansColumns);

        Map<String, String> hrempColumnsMap = new HashMap<>();
        hrempColumnsMap.put("First Name", "M.[First Name]");
        hrempColumnsMap.put("Middle Name", "M.[Middle Name]"); 
        hrempColumnsMap.put("Last Name", "M.[Other Names]");
        hrempColumnsMap.put("Initials", "M.Salutation");
        hrempColumnsMap.put("Full Name", "M.[Name]");
        hrempColumnsMap.put("Postal Address", "M.[Current Address]");
        hrempColumnsMap.put("Residential Address", "M.[Home Address]");
        hrempColumnsMap.put("City", "M.[City]");
        hrempColumnsMap.put("Post Code", "M.[Current Address]");
        hrempColumnsMap.put("County", "M.[County]");
        hrempColumnsMap.put("Country", "M.[Country_Region Code]");
        hrempColumnsMap.put("Home Phone Number", "M.[Phone No_]");
        hrempColumnsMap.put("Cellular Phone Number", "M.[Phone No_]");
        hrempColumnsMap.put("Work Phone Number", "M.[Phone No_]");
        hrempColumnsMap.put("E-Mail", "M.[E-Mail]");
        hrempColumnsMap.put("Social Security Number", "M.[Primary Identification]");
        hrempColumnsMap.put("Driver's License Number", "M.[Secondary Identification]");
        hrempColumnsMap.put("Passport Number", "M.[Secondary Identification]");
        hrempColumnsMap.put("Birth Date", "M.[Date of Birth]");
        tableColumnMappings.put("[CRONUS International Ltd_$HR Employees$aebc043a-a98e-45f6-8dce-4c8bdabf97fd]", hrempColumnsMap);
    }

    public static String updateMultipleTables(List<String> tables) {
        try (Connection conn = DatabaseConfig.getTargetConnection()) {
            // Start transaction
            conn.setAutoCommit(false);

            StringJoiner updateSummary = new StringJoiner(", ");

            for (String table : tables) {
                // Get the column mappings for the given table
                Map<String, String> columnMappings = tableColumnMappings.get(table);

                if (columnMappings == null) {
                    logger.warn("No column mappings found for table: {}", table);
                    return "Update failed: No column mappings found for table " + table;
                }

                // Build dynamic SET clause using StringJoiner
                StringJoiner setClause = new StringJoiner(", ");
                for (Map.Entry<String, String> entry : columnMappings.entrySet()) {
                    setClause.add("[" + entry.getKey() + "] = " + entry.getValue());
                }

                String sql = "UPDATE " + table + " " +  
                             "SET " + setClause.toString() + " " +
                             "FROM " + table + " AS T " +
                             "INNER JOIN [CRONUS International Ltd_$Members$aebc043a-a98e-45f6-8dce-4c8bdabf97fd] M " +
                             "ON T.[Member No_] = M.[No_];";
                             
                logger.info("Generated SQL for table {}: {}", table, sql);

                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    int affectedRows = pstmt.executeUpdate();
                    updateSummary.add(table + " (" + affectedRows + " rows updated)");
                    logger.info("Updated {} rows in {}", affectedRows, table);
                } catch (SQLException e) {
                    logger.error("Error updating table {}: {}", table, e.getMessage(), e);
                    try {
                        // Ensure rollback on the same connection
                        conn.rollback(); 
                        logger.warn("Transaction rolled back due to error while updating {}", table);
                    } catch (SQLException rollbackEx) {
                        logger.error("Rollback error: {}", rollbackEx.getMessage(), rollbackEx);
                    }
                    return "Update failed: " + e.getMessage();
                }
            }

            // Commit transaction if all updates succeed
            conn.commit(); 
            logger.info("All updates applied successfully.");
            return "Updates applied successfully: " + updateSummary.toString();

        } catch (SQLException e) {
            logger.error("Transaction failed: {}", e.getMessage(), e);
            return "Update failed: " + e.getMessage();
        }
    }
}
