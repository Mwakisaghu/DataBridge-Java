package com.databridge;

import java.util.HashMap;
import java.util.Map;

import com.databridge.service.DataTransformer;

public class DataTransformerTest {
    public static void main(String[] args) {
        Map<String, Object> row = new HashMap<>();
        row.put("email", "old@example.com");
        row.put("phone", "1234567890");
        row.put("dob", "1990-01-01");

        Map<String, String> transformations = new HashMap<>();
        transformations.put("email", "faker.internet.email_address");  // Corrected key
        transformations.put("phone", "faker.phone_number");
        transformations.put("dob", "faker.date_of_birth");

        System.out.println("Before Transformation: " + row);
        Map<String, Object> transformedRow = DataTransformer.applyTransformations(row, transformations);
        System.out.println("After Transformation: " + transformedRow);

        // Ensure executor is closed after transformation
        DataTransformer.shutdownExecutor();
    }
}
