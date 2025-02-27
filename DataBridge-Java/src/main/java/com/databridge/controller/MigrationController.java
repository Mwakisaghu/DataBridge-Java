package com.databridge.controller;

import com.databridge.config.JsonSchemaLoader;
import com.databridge.service.DataExtractor;
import com.databridge.service.DataInserter;
import com.databridge.service.DataTransformer;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MigrationController {
    private static final Logger logger = LoggerFactory.getLogger(MigrationController.class);

    public String migrateTable(String tableName) {
        try {
            // Load schema mappings
            Map<String, JsonNode> schema = JsonSchemaLoader.loadSchema();
            if (schema.isEmpty()) {
                logger.error("Schema mappings not found!");
                return "Schema mappings not found!";
            }

            // Ensure "tables" node exists
            JsonNode tablesNode = schema.get("tables");
            if (tablesNode == null) {
                logger.error("No table mappings found in schema.");
                return "No table mappings found!";
            }

            // Ensure the requested table exists
            JsonNode tableMapping = tablesNode.get(tableName);
            if (tableMapping == null) {
                logger.error("Table mapping not found for table: {}", tableName);
                return "Table mapping not found!";
            }

            // Extract source and target table names
            String sourceTable = tableMapping.get("source_table").asText();
            String targetTable = tableMapping.get("target_table").asText();
            JsonNode columnsNode = tableMapping.get("columns");

            if (columnsNode == null || columnsNode.isEmpty()) {
                logger.error("No column mappings found for table: {}", tableName);
                return "No column mappings found!";
            }

            // Convert JsonNode to Map<String, String>
            Map<String, String> transformationMap = new HashMap<>();
            columnsNode.fields().forEachRemaining(entry -> transformationMap.put(entry.getKey(), entry.getValue().asText()));

            logger.info("Starting data extraction for source table: {}", sourceTable);
            List<Map<String, Object>> data = DataExtractor.fetchData(sourceTable);

            logger.info("Applying transformations for table: {}", tableName);
            for (Map<String, Object> row : data) {
                DataTransformer.applyTransformations(row, transformationMap);
            }

            logger.info("Inserting transformed data into target table: {}", targetTable);
            DataInserter.insertData(targetTable, data);

            logger.info("Data migration for table '{}' completed successfully.", tableName);
            return "Data migration for " + tableName + " completed!";
        } catch (Exception e) {
            logger.error("Migration failed for table '{}': {}", tableName, e.getMessage(), e);
            return "Migration failed: " + e.getMessage();
        }
    }
}

