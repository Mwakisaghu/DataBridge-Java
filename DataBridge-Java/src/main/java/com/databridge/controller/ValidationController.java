package com.databridge.controller;

import com.databridge.validator.MembersExcelValidator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class ValidationController {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public String validateExcel(InputStream inputStream) {
        try {
            MembersExcelValidator validator = new MembersExcelValidator();
            Map<Integer, List<String>> errors = validator.validate(inputStream);

            if (errors.isEmpty()) {
                return objectMapper.writeValueAsString(Map.of(
                    "status", "success",
                    "message", "No validation errors."
                ));
            } else {
                return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "Validation failed.",
                    "errors", errors
                ));
            }
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "Validation exception: " + e.getMessage()
                ));
            } catch (Exception serializationException) {
                // Fallback in case of JSON failure
                return "{\"status\":\"error\",\"message\":\"Unknown error during validation.\"}";
            }
        }
    }
}