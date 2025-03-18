package com.databridge.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.SQLException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class DatabaseConfig {
    private static HikariDataSource sourceDataSource;
    private static HikariDataSource targetDataSource;

    static {
        loadConfig();
    }

    private static void loadConfig() {
        try {
            File file = new File("");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new FileInputStream(file));
            doc.getDocumentElement().normalize();

            // Load source database configuration
            Element source = (Element) doc.getElementsByTagName("source").item(0);
            String sourceDbUrl = source.getElementsByTagName("url").item(0).getTextContent();
            String sourceDbUser  = source.getElementsByTagName("user").item(0).getTextContent();
            String sourceDbPassword = source.getElementsByTagName("password").item(0).getTextContent();

            // Load target database configuration
            Element target = (Element) doc.getElementsByTagName("target").item(0);
            String targetDbUrl = target.getElementsByTagName("url").item(0).getTextContent();
            String targetDbUser  = target.getElementsByTagName("user").item(0).getTextContent();
            String targetDbPassword = target.getElementsByTagName("password").item(0).getTextContent();

            HikariConfig sourceConfig = new HikariConfig();
            sourceConfig.setJdbcUrl(sourceDbUrl);
            sourceConfig.setUsername(sourceDbUser );
            sourceConfig.setPassword(sourceDbPassword);
            sourceConfig.setMaximumPoolSize(10);
            sourceConfig.setConnectionTimeout(30000);
            sourceConfig.setIdleTimeout(600000);
            sourceConfig.setMaxLifetime(1800000); // 30 minutes
            sourceDataSource = new HikariDataSource(sourceConfig);

            HikariConfig targetConfig = new HikariConfig();
            targetConfig.setJdbcUrl(targetDbUrl);
            targetConfig.setUsername(targetDbUser );
            targetConfig.setPassword(targetDbPassword);
            targetConfig.setMaximumPoolSize(10);
            targetConfig.setConnectionTimeout(30000);
            targetConfig.setIdleTimeout(600000);
            targetConfig.setMaxLifetime(1800000); // 30 minutes
            targetDataSource = new HikariDataSource(targetConfig);
        } catch (Exception e) {
            System.err.println("Error loading database configuration: " + e.getMessage());
        }
    }

    public static Connection getSourceConnection() {
        try {
            return sourceDataSource.getConnection();
        } catch (SQLException e) {
            System.err.println("Failed to get source database connection: " + e.getMessage());
            return null;
        }
    }

    public static Connection getTargetConnection() {
        try {
            return targetDataSource.getConnection();
        } catch (SQLException e) {
            System.err.println("Failed to get target database connection: " + e.getMessage());
            return null;
        }
    }

    public static void close() {
        if (sourceDataSource != null) {
            sourceDataSource.close();
        }
        if (targetDataSource != null) {
            targetDataSource.close();
        }
    }
}