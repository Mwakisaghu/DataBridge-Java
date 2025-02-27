package com.databridge.controller;

import com.databridge.config.JsonSchemaLoader;
import com.databridge.service.DataExtractor;
import com.databridge.service.DataInserter;
import com.databridge.service.DataTransformer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MigrationController {
    private static final Logger logger = LoggerFactory.getLogger(MigrationController.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public String migrateTable(String tableName) {
        try {
            // Load schema mappings
            Map<String, JsonNode> schema = JsonSchemaLoader.loadSchema();
            if (schema.isEmpty()) {
                logger.error("Schema mappings not found!");
                return "Schema mappings not found!";
            }

            JsonNode tablesNode = schema.get("tables");
            if (tablesNode == null) {
                logger.error("No table mappings found in schema.");
                return "No table mappings found!";
            }
            JsonNode tableMapping = tablesNode.get(tableName);
            if (tableMapping == null) {
                logger.error("Table mapping not found for '{}'.", tableName);
                return "Table mapping not found for " + tableName;
            }

            // Extract table details
            String sourceTable = tableMapping.get("source_table").asText();
            String targetTable = tableMapping.get("target_table").asText();
            JsonNode columnsNode = tableMapping.get("columns");

            if (columnsNode == null) {
                logger.error("No column mappings found for table '{}'", tableName);
                return "No column mappings found!";
            }

            // Extract transformations from columns
            Map<String, String> transformationMap = new HashMap<>();
            columnsNode.fields().forEachRemaining(entry -> {
                JsonNode columnNode = entry.getValue();
                String sourceColumn = columnNode.get("source_column").asText();
                if (columnNode.has("transformation")) {
                    transformationMap.put(sourceColumn, columnNode.get("transformation").asText());
                }
            });

            // Log transformations before processing
            logger.info("Transformations for table '{}': {}", tableName, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(transformationMap));

            logger.info("Starting data extraction for source table: {}", sourceTable);
            List<Map<String, Object>> data = DataExtractor.fetchData(sourceTable);

            if (data == null || data.isEmpty()) {
                logger.warn("No data extracted from '{}'. Skipping migration.", sourceTable);
                return "No data extracted from " + sourceTable;
            }

            if (transformationMap.isEmpty()) {
                logger.warn("No transformations defined for '{}'. Skipping transformations.", tableName);
            } else {
                logger.info("Applying transformations for table: {}", tableName);
                for (Map<String, Object> row : data) {
                    if (row == null) {
                        logger.warn("Encountered null row in data extraction. Skipping...");
                        continue;
                    }
                    DataTransformer.applyTransformations(row, transformationMap);
                }
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