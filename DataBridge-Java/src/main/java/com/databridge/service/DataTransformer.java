package com.databridge.service;

import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

public class DataTransformer {
    private static final Logger logger = LoggerFactory.getLogger(DataTransformer.class);
    private static final Faker faker = new Faker();
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10); // Parallel execution

    public static Map<String, Object> applyTransformations(Map<String, Object> row, Map<String, String> transformations) {
        for (Map.Entry<String, String> entry : transformations.entrySet()) {
            String field = entry.getKey();
            String transformation = entry.getValue();

            try {
                switch (transformation) {
                    case "faker.email":
                        row.put(field, faker.internet().emailAddress());
                        break;
                    case "faker.phone_number":
                        row.put(field, faker.phoneNumber().cellPhone());
                        break;
                    case "faker.date_of_birth":
                        row.put(field, faker.date().birthday());
                        break;
                    default:
                        if (transformation.startsWith("faker.date_between")) {
                            row.put(field, generateFakeDate(transformation));
                        } else {
                            logger.warn("Unknown transformation '{}'", transformation);
                        }
                        break;
                }
            } catch (Exception e) {
                logger.error("Error applying transformation '{}' to field '{}': {}", transformation, field, e.getMessage(), e);
            }
        }
        return row;
    }

    public static List<Map<String, Object>> transformDataInParallel(List<Map<String, Object>> dataList, Map<String, String> transformations) {
        List<Future<Map<String, Object>>> futures = new ArrayList<>();

        for (Map<String, Object> row : dataList) {
            futures.add(executorService.submit(() -> applyTransformations(row, transformations)));
        }

        List<Map<String, Object>> transformedData = new ArrayList<>();
        for (Future<Map<String, Object>> future : futures) {
            try {
                transformedData.add(future.get());
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error during parallel data transformation: {}", e.getMessage(), e);
            }
        }
        return transformedData;
    }

    private static Date generateFakeDate(String transformation) {
        try {
            String[] parts = transformation.split(":");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid format. Expected 'faker.date_between:startDate,endDate'");
            }

            String[] range = parts[1].split(",");
            if (range.length != 2) {
                throw new IllegalArgumentException("Date range must have a start and end date.");
            }

            LocalDate start = LocalDate.parse(range[0].trim());
            LocalDate end = LocalDate.parse(range[1].trim());

            return faker.date().between(convertToDate(start), convertToDate(end));
        } catch (Exception e) {
            logger.error("Invalid date transformation format '{}': {}", transformation, e.getMessage(), e);
            return null;
        }
    }

    private static Date convertToDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
