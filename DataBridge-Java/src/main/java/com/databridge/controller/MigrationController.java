package com.databridge.controller;

import com.databridge.config.JsonSchemaLoader;
import com.databridge.service.DataExtractor;
import com.databridge.service.DataInserter;
import com.databridge.service.DataTransformer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class MigrationController {

    public String migrateTable(String tableName) throws IOException {
        try {
            // Load schema mappings
            Map<String, JsonNode> schema = JsonSchemaLoader.loadSchema();
            if (schema == null) return "Schema mappings not found!";

            // Get mapping for the requested table
            JsonNode tableMapping = schema.get(tableName);
            if (tableMapping == null) return "Table mapping not found!";

            // Extract table details
            String sourceTable = tableMapping.get("source_table").asText();
            String targetTable = tableMapping.get("target_table").asText();
            JsonNode transformations = tableMapping.get("transformations");

            // Convert JsonNode to Map<String, String> before passing to applyTransformations
            @SuppressWarnings("unchecked")
            Map<String, String> transformationMap = new ObjectMapper().convertValue(transformations, Map.class);

            // Extract data from source
            List<Map<String, Object>> data = DataExtractor.fetchData(sourceTable);

            // Apply transformations
            for (Map<String, Object> row : data) {
                DataTransformer.applyTransformations(row, transformationMap);
            }

            // Insert transformed data into target
            DataInserter.insertData(targetTable, data);
            
            return "Data migration for " + tableName + " completed!";
        } catch (SQLException e) {
            return "Migration failed: " + e.getMessage();
        }
    }
}
