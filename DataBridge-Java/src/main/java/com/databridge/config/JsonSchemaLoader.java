package com.databridge.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

public class JsonSchemaLoader {
    private static final Logger logger = LoggerFactory.getLogger(JsonSchemaLoader.class);
    private static final String SCHEMA_FILE = "schema_mappings.json";

    public static Map<String, JsonNode> loadSchema() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream inputStream = JsonSchemaLoader.class.getClassLoader().getResourceAsStream(SCHEMA_FILE)) {
            if (inputStream == null) {
                logger.error("Schema file not found: {}", SCHEMA_FILE);
                // Return an empty map instead of throwing an exception
                return Collections.emptyMap();  
            }
            JsonNode jsonNode = mapper.readTree(inputStream);
            logger.info("Successfully loaded schema file: {}", SCHEMA_FILE);
            return mapper.convertValue(jsonNode, new TypeReference<Map<String, JsonNode>>() {});
        } catch (IOException e) {
            logger.error("Error loading schema file: {}", SCHEMA_FILE, e);
            // Return an empty map - prevents application failure
            return Collections.emptyMap();  
        }
    }
}
