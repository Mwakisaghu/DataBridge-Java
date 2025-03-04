package com.databridge.service;

import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DataTransformer {
    private static final Logger logger = LoggerFactory.getLogger(DataTransformer.class);
    private static final Faker faker = new Faker();
    private static final Pattern OPTIONS_PATTERN = Pattern.compile("options\\[(.*?)\\]");
    private static final ExecutorService executorService = Executors.newFixedThreadPool(5);

    public static Map<String, Object> applyTransformations(Map<String, Object> row, Map<String, String> transformations, Map<String, Integer> columnLengths) {
        if (row == null || transformations == null || columnLengths == null) {
            logger.warn("Null input provided to applyTransformations");
            return row;
        }

        logger.debug("Row before transformation: {}", row);

        for (Map.Entry<String, String> entry : transformations.entrySet()) {
            String field = entry.getKey();
            String transformation = entry.getValue();

            if (!row.containsKey(field)) {
                logger.warn("Field '{}' not found in row. Skipping transformation.", field);
                continue;
            }

            if (transformation == null || transformation.trim().isEmpty()) {
                logger.warn("Empty transformation rule for field '{}'", field);
                continue;
            }

            try {
                Object oldValue = row.get(field);
                logger.info("Original Value for '{}': {}", field, oldValue);

                String newValue = applyTransformation(transformation, oldValue);

                if (newValue != null && columnLengths.containsKey(field)) {
                    int maxLength = columnLengths.get(field);
                    if (newValue.length() > maxLength) {
                        logger.warn("Truncating '{}' from '{}' to '{}' (Max Length: {})", field, newValue, newValue.substring(0, maxLength), maxLength);
                        newValue = newValue.substring(0, maxLength);
                    }
                }

                if (newValue != null) {
                    row.put(field, newValue);
                    logger.info("Transformed '{}' from '{}' to '{}'", field, oldValue, newValue);
                }
            } catch (Exception e) {
                logger.error("Error applying transformation '{}' to field '{}': {}", transformation, field, e.getMessage(), e);
            }
        }

        logger.debug("Row after transformation: {}", row);
        return row;
    }

    private static String applyTransformation(String transformation, Object oldValue) {
        if (transformation.startsWith("faker.number.digits:")) {
            int numDigits = Integer.parseInt(transformation.replace("faker.number.digits:", "").trim());
            return faker.number().digits(numDigits);
        }
    
        switch (transformation) {
            case "faker.name.name":
                return faker.name().name();
            case "faker.name.full_name":
                return faker.name().fullName();
            case "faker.name.first_name":
                return faker.name().firstName();
            case "faker.address.full_address":
                return faker.address().fullAddress();
            case "faker.address.state":
                return faker.address().state();
            case "faker.address.country":
                return faker.address().country();
            case "faker.address.city":
                return faker.address().city();
            case "faker.internet.email_address":
                return faker.internet().emailAddress();
            case "faker.phone_number":
                return faker.phoneNumber().cellPhone();
            case "faker.job.title":
                return faker.job().title();
            case "faker.work.station":
                return faker.company().name();
            case "faker.date_of_birth":
                return new SimpleDateFormat("yyyy-MM-dd").format(faker.date().birthday());
    
            case "combine.names":
                String firstName = faker.name().firstName();
                String middleName = faker.name().firstName();
                String otherNames = faker.name().firstName();
                return firstName + " " + middleName + " " + otherNames;
    
            default:
                return parseCustomTransformation(transformation, oldValue);
        }
    }
    

    private static String parseCustomTransformation(String transformation, Object oldValue) {
        Matcher optionsMatcher = OPTIONS_PATTERN.matcher(transformation);
        if (optionsMatcher.matches()) {
            String[] options = optionsMatcher.group(1).split(",");
            return options[new Random().nextInt(options.length)].trim();
        }

        if (transformation.startsWith("faker.date_between")) {
            return Optional.ofNullable(generateFakeDate(transformation))
                    .map(date -> new SimpleDateFormat("yyyy-MM-dd").format(date))
                    .orElse(null);
        }

        logger.warn("Unknown transformation '{}'", transformation);
        return oldValue != null ? oldValue.toString() : null;
    }

    private static Date generateFakeDate(String transformation) {
        try {
            String dateRange = transformation.replace("faker.date_between[", "").replace("]", "").trim();
            String[] parts = dateRange.split(",");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

            if (parts.length == 2) {
                Date startDate = sdf.parse(parts[0].trim());
                Date endDate = sdf.parse(parts[1].trim());
                return faker.date().between(startDate, endDate);
            }
        } catch (Exception e) {
            logger.error("Error parsing date range for transformation '{}': {}", transformation, e.getMessage(), e);
        }
        return null;
    }

    public static void shutdownExecutor() {
        logger.info("Shutting down Executor Service...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("Executor Service did not terminate properly.");
                }
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
