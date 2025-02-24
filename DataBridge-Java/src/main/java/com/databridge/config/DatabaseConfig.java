package com.databridge.config;

import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class DatabaseConfig {
    private static String sourceDbUrl;
    private static String sourceDbUser;
    private static String sourceDbPassword;

    private static String targetDbUrl;
    private static String targetDbUser;
    private static String targetDbPassword;

    static {
        loadConfig();
    }

    private static void loadConfig() {
        try {
            File file = new File("config.xml");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new FileInputStream(file));
            doc.getDocumentElement().normalize();

            Element source = (Element) doc.getElementsByTagName("source").item(0);
            sourceDbUrl = source.getElementsByTagName("url").item(0).getTextContent();
            sourceDbUser = source.getElementsByTagName("user").item(0).getTextContent();
            sourceDbPassword = source.getElementsByTagName("password").item(0).getTextContent();

            Element target = (Element) doc.getElementsByTagName("target").item(0);
            targetDbUrl = target.getElementsByTagName("url").item(0).getTextContent();
            targetDbUser = target.getElementsByTagName("user").item(0).getTextContent();
            targetDbPassword = target.getElementsByTagName("password").item(0).getTextContent();
        } catch (Exception e) {
            System.err.println("Error loading database configuration: " + e.getMessage());
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
            Properties props = new Properties();
            props.setProperty("user", user);
            props.setProperty("password", password);
            return DriverManager.getConnection(url, props);
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());
            return null;
        }
    }
}
