package com.databridge.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.util.Collections;
import java.util.Map;

public class JsonSchemaLoader {
    private static final Logger logger = LoggerFactory.getLogger(JsonSchemaLoader.class);

    private static final String CONFIG_FILE = "DataBridge-Java\\src\\resources\\config.xml";

    /**
     * Loads the <schema> path from the config.xml file
     */
    private static String getSchemaFilePathFromConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(configFile);
            doc.getDocumentElement().normalize();

            NodeList schemaList = doc.getElementsByTagName("schema");
            if (schemaList.getLength() > 0) {
                String path = schemaList.item(0).getTextContent().trim();
                logger.info("Schema file path loaded from config: {}", path);
                return path;
            }
        } catch (Exception e) {
            logger.error("Failed to read schema path from config: {}", CONFIG_FILE, e);
        }
        return null;
    }

    /**
     * Loads the JSON schema mappings as a Map<String, JsonNode>
     */
    public static Map<String, JsonNode> loadSchema() {
        ObjectMapper mapper = new ObjectMapper();
        String schemaFilePath = getSchemaFilePathFromConfig();

        if (schemaFilePath == null) {
            logger.warn("Schema file path is missing in config.");
            return Collections.emptyMap();
        }

        try (FileInputStream inputStream = new FileInputStream(new File(schemaFilePath))) {
            JsonNode jsonNode = mapper.readTree(inputStream);
            logger.info("Successfully loaded schema file: {}", schemaFilePath);
            return mapper.convertValue(jsonNode, new TypeReference<Map<String, JsonNode>>() {});
        } catch (IOException e) {
            logger.error("Error loading schema file: {}", schemaFilePath, e);
            return Collections.emptyMap();
        }
    }
}
