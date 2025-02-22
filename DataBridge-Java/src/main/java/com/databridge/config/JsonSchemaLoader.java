package com.databridge.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class JsonSchemaLoader {
    private static final String SCHEMA_FILE = "schema_mappings.json";

    public static Map<String, JsonNode> loadSchema() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream inputStream = JsonSchemaLoader.class.getClassLoader().getResourceAsStream(SCHEMA_FILE)) {
            if (inputStream == null) {
                throw new IOException("Schema file not found: " + SCHEMA_FILE);
            }
            JsonNode jsonNode = mapper.readTree(inputStream);
            return mapper.convertValue(jsonNode, new TypeReference<Map<String, JsonNode>>() {});
        } catch (IOException e) {
            System.err.println("Error loading schema: " + e.getMessage());
            throw new RuntimeException("Failed to load schema mappings", e);
        }
    }
}
