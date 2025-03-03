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
        transformations.put("email", "faker.internet.email_address");
        transformations.put("phone", "faker.phone_number");
        transformations.put("dob", "faker.date_of_birth");

        Map<String, Integer> columnLengths = new HashMap<>();
        columnLengths.put("email", 50);
        columnLengths.put("phone", 15);
        columnLengths.put("dob", 10);

        System.out.println("Before Transformation: " + row);
        System.out.println("Column Lengths: " + columnLengths);

        Map<String, Object> transformedRow = DataTransformer.applyTransformations(row, transformations, columnLengths);
        
        System.out.println("After Transformation: " + transformedRow);

        // Ensure executor is closed after transformation
        DataTransformer.shutdownExecutor();
    }
}
