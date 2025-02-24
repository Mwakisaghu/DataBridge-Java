package com.databridge.controller;

import com.databridge.config.JsonSchemaLoader;
import com.databridge.service.DataExtractor;
import com.databridge.service.DataInserter;
import com.databridge.service.DataTransformer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

            // Get mapping for the requested table
            JsonNode tableMapping = schema.get(tableName);
            if (tableMapping == null) {
                logger.error("Table mapping not found for table: {}", tableName);
                return "Table mapping not found!";
            }

            // Extract table details
            String sourceTable = tableMapping.get("source_table").asText();
            String targetTable = tableMapping.get("target_table").asText();
            JsonNode transformations = tableMapping.get("transformations");

            Map<String, String> transformationMap = objectMapper.convertValue(
                transformations, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}
            );

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
