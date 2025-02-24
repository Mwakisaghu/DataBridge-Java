package com.databridge.config;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConfig {
    private static String sourceDbUrl;
    private static String sourceDbUser;
    private static String sourceDbPassword;
    
    private static String targetDbUrl;
    private static String targetDbUser;
    private static String targetDbPassword;

    // Load configurations when the class is initialized
    static {
        loadConfigurations();
    }

    private static void loadConfigurations() {
        try (InputStream input = DatabaseConfig.class.getClassLoader().getResourceAsStream("config.xml")) {
            if (input == null) {
                throw new RuntimeException("Configuration file 'config.xml' not found in resources.");
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(input);
            document.getDocumentElement().normalize();

            Element root = document.getDocumentElement();
            NodeList sourceDb = root.getElementsByTagName("source");
            NodeList targetDb = root.getElementsByTagName("target");

            if (sourceDb.getLength() > 0) {
                Node node = sourceDb.item(0);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element source = (Element) node;
                    sourceDbUrl = source.getElementsByTagName("url").item(0).getTextContent();
                    sourceDbUser = source.getElementsByTagName("user").item(0).getTextContent();
                    sourceDbPassword = source.getElementsByTagName("password").item(0).getTextContent();
                }
            }

            if (targetDb.getLength() > 0) {
                Node node = targetDb.item(0);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element target = (Element) node;
                    targetDbUrl = target.getElementsByTagName("url").item(0).getTextContent();
                    targetDbUser = target.getElementsByTagName("user").item(0).getTextContent();
                    targetDbPassword = target.getElementsByTagName("password").item(0).getTextContent();
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Error loading database configurations: " + e.getMessage(), e);
        }
    }

    public static Connection getSourceConnection() {
        return createConnection(sourceDbUrl, sourceDbUser, sourceDbPassword);
    }

    public static Connection getTargetConnection() {
        return createConnection(targetDbUrl, targetDbUser, targetDbPassword);
    }

    private static Connection createConnection(String url, String user, String password) {
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());
            return null;
        }
    }
}
