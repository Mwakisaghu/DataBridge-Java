package com.databridge.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

public class JsonSchemaLoader {
    private static final Logger logger = LoggerFactory.getLogger(JsonSchemaLoader.class);
    private static final String SCHEMA_FILE = "E:\\DataBridge-Java\\DataBridge-Java\\src\\resources\\schema_mappings.json";

    public static Map<String, JsonNode> loadSchema() {
        ObjectMapper mapper = new ObjectMapper();
        try (FileInputStream inputStream = new FileInputStream(new File(SCHEMA_FILE))) {
            JsonNode jsonNode = mapper.readTree(inputStream);
            logger.info("Successfully loaded schema file: {}", SCHEMA_FILE);
            return mapper.convertValue(jsonNode, new TypeReference<Map<String, JsonNode>>() {});
        } catch (IOException e) {
            logger.error("Error loading schema file: {}", SCHEMA_FILE, e);
            return Collections.emptyMap();
        }
    }
}
