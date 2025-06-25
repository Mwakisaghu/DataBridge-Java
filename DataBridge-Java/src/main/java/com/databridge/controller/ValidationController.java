package com.databridge.controller;

import com.databridge.util.LogStorage;
import com.databridge.validator.MembersExcelValidator;
import com.databridge.validator.ChartOfAccountsExcelValidator;
import com.databridge.validator.CustomerExcelValidator;
import com.databridge.validator.NomineeExcelValidator;
import com.databridge.validator.VendorExcelValidator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class ValidationController {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public String validateExcel(InputStream inputStream) {
        LogStorage.addLog("info", "Started validating members file");

        try {
            MembersExcelValidator validator = new MembersExcelValidator();
            Map<Integer, List<String>> errors = validator.validate(inputStream);

            if (errors.isEmpty()) {
                LogStorage.addLog("info", "Members file validation passed with no errors");
                return objectMapper.writeValueAsString(Map.of(
                        "status", "success",
                        "message", "No validation errors."
                ));
            } else {
                LogStorage.addLog("warning", "Members file validation failed with errors");
                return objectMapper.writeValueAsString(Map.of(
                        "status", "error",
                        "message", "Validation failed.",
                        "errors", errors
                ));
            }
        } catch (Exception e) {
            LogStorage.addLog("error", "Exception during members file validation: " + e.getMessage());
            try {
                return objectMapper.writeValueAsString(Map.of(
                        "status", "error",
                        "message", "Validation exception: " + e.getMessage()
                ));
            } catch (Exception serializationException) {
                LogStorage.addLog("error", "Serialization error after validation failure");
                return "{\"status\":\"error\",\"message\":\"Unknown error during validation.\"}";
            }
        }
    }

    public String validateChartOfAccounts(InputStream inputStream) {
        LogStorage.addLog("info", "Started validating chart of accounts file");

        try {
            ChartOfAccountsExcelValidator validator = new ChartOfAccountsExcelValidator();
            Map<Integer, List<String>> errors = validator.validate(inputStream);

            if (errors.isEmpty()) {
                LogStorage.addLog("info", "Chart of accounts validation passed with no errors");
                return objectMapper.writeValueAsString(Map.of(
                        "status", "success",
                        "message", "No validation errors in Chart of Accounts."
                ));
            } else {
                LogStorage.addLog("warning", "Chart of accounts validation failed with errors");
                return objectMapper.writeValueAsString(Map.of(
                        "status", "error",
                        "message", "Chart of Accounts validation failed.",
                        "errors", errors
                ));
            }
        } catch (Exception e) {
            LogStorage.addLog("error", "Exception during Chart of Accounts validation: " + e.getMessage());
            try {
                return objectMapper.writeValueAsString(Map.of(
                        "status", "error",
                        "message", "Chart of Accounts validation exception: " + e.getMessage()
                ));
            } catch (Exception serializationException) {
                LogStorage.addLog("error", "Serialization error after Chart of Accounts validation failure");
                return "{\"status\":\"error\",\"message\":\"Unknown error during Chart of Accounts validation.\"}";
            }
        }
    }

    public String validateNominees(InputStream inputStream) {
        LogStorage.addLog("info", "Started validating nominee/beneficiary file");

        try {
            NomineeExcelValidator validator = new NomineeExcelValidator();
            Map<Integer, List<String>> errors = validator.validate(inputStream);

            if (errors.isEmpty()) {
                LogStorage.addLog("info", "Nominee file validation passed with no errors");
                return objectMapper.writeValueAsString(Map.of(
                        "status", "success",
                        "message", "No validation errors in nominee file."
                ));
            } else {
                LogStorage.addLog("warning", "Nominee file validation failed with errors");
                return objectMapper.writeValueAsString(Map.of(
                        "status", "error",
                        "message", "Nominee file validation failed.",
                        "errors", errors
                ));
            }
        } catch (Exception e) {
            LogStorage.addLog("error", "Exception during nominee file validation: " + e.getMessage());
            try {
                return objectMapper.writeValueAsString(Map.of(
                        "status", "error",
                        "message", "Nominee file validation exception: " + e.getMessage()
                ));
            } catch (Exception serializationException) {
                LogStorage.addLog("error", "Serialization error after nominee validation failure");
                return "{\"status\":\"error\",\"message\":\"Unknown error during nominee file validation.\"}";
            }
        }
    }

    public String validateCustomers(InputStream inputStream) {
    LogStorage.addLog("info", "Started validating customer static data file");

    try {
        CustomerExcelValidator validator = new CustomerExcelValidator();
        Map<Integer, List<String>> errors = validator.validate(inputStream);

        if (errors.isEmpty()) {
            LogStorage.addLog("info", "Customer static data validation passed with no errors");
            return objectMapper.writeValueAsString(Map.of(
                    "status", "success",
                    "message", "No validation errors in customer data."
            ));
        } else {
            LogStorage.addLog("warning", "Customer static data validation failed with errors");
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "Customer data validation failed.",
                    "errors", errors
            ));
        }
    } catch (Exception e) {
        LogStorage.addLog("error", "Exception during customer data validation: " + e.getMessage());
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "Customer data validation exception: " + e.getMessage()
            ));
        } catch (Exception serializationException) {
            LogStorage.addLog("error", "Serialization error after customer validation failure");
            return "{\"status\":\"error\",\"message\":\"Unknown error during customer data validation.\"}";
            }
        }
    }

    public String validateVendors(InputStream inputStream) {
    LogStorage.addLog("info", "Started validating vendor static data file");

    try {
        VendorExcelValidator validator = new VendorExcelValidator();
        Map<Integer, List<String>> errors = validator.validate(inputStream);

        if (errors.isEmpty()) {
            LogStorage.addLog("info", "Vendor static data validation passed with no errors");
            return objectMapper.writeValueAsString(Map.of(
                    "status", "success",
                    "message", "No validation errors in vendor data."
            ));
        } else {
            LogStorage.addLog("warning", "Vendor static data validation failed with errors");
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "Vendor data validation failed.",
                    "errors", errors
            ));
        }
    } catch (Exception e) {
        LogStorage.addLog("error", "Exception during vendor data validation: " + e.getMessage());
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", "Vendor data validation exception: " + e.getMessage()
            ));
        } catch (Exception serializationException) {
            return "{\"status\":\"error\",\"message\":\"Unknown error during vendor validation.\"}";
            }
        }
    }
}