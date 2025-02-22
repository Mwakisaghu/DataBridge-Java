package com.databridge.service;

import net.datafaker.Faker;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;

public class DataTransformer {
    private static final Faker faker = new Faker(); // Using `net.datafaker.Faker`

    public static Map<String, Object> applyTransformations(Map<String, Object> row, Map<String, String> transformations) {
        for (Map.Entry<String, String> entry : transformations.entrySet()) {
            String field = entry.getKey();
            String transformation = entry.getValue();

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
                        try {
                            String[] range = transformation.split(":")[1].split(",");
                            Date startDate = convertToDate(LocalDate.parse(range[0].trim()));
                            Date endDate = convertToDate(LocalDate.parse(range[1].trim()));

                            Date fakeDate = faker.date().between(startDate, endDate);

                            row.put(field, fakeDate);
                        } catch (Exception e) {
                            System.err.println("Invalid date range format for: " + transformation);
                        }
                    }
                    break;
            }
        }
        return row;
    }

    private static Date convertToDate(LocalDate localDate) {
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
