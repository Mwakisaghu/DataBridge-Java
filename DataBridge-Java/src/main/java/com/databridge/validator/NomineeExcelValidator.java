package com.databridge.validator;

import com.databridge.util.LogStorage;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;

public class NomineeExcelValidator {

    private final Map<Integer, List<String>> rowErrors = new HashMap<>();
    private final Set<String> seenMemberNos = new HashSet<>();
    private final List<Double> allocations = new ArrayList<>();

    public Map<Integer, List<String>> validate(InputStream inputStream) {
        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);

            Map<String, Integer> columnMap = new HashMap<>();
            for (Cell cell : headerRow) {
                columnMap.put(cell.getStringCellValue().trim(), cell.getColumnIndex());
            }

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                int rowNum = i + 1;

                String memberNo = getCell(row, columnMap.get("Member no"));
                if (memberNo.isEmpty()) {
                    logError(rowNum, "'Member No' is required");
                    continue;
                }

                // Duplicate check - Member No
                if (!seenMemberNos.add(memberNo)) {
                    logError(rowNum, "'Member No' is duplicated: " + memberNo);
                }

                //Boolean fields
                List<String> booleanFields = List.of("Is a Beneficiary", "Is Nominee", "Is Contact Person", "Removed", "Is A WitNess");
                for (String field : booleanFields) {
                    validateBoolean(field, row, columnMap, rowNum, memberNo);
                }

                //Email, Phone, Allocation
                validateEmail(row, columnMap.get("Email"), rowNum, memberNo);
                validatePhone(row, columnMap.get("Mobile No"), rowNum, memberNo);
                validateNumeric("Allocation", row, columnMap, rowNum, memberNo);

                // Allocation
                String allocationVal = getCell(row, columnMap.get("Allocation"));
                if (!allocationVal.isEmpty()) {
                    try {
                        allocations.add(Double.parseDouble(allocationVal));
                    } catch (NumberFormatException ignored) {}
                }

                // Date of Birth
                validateDate(row.getCell(columnMap.get("Date of Birth")), rowNum, memberNo);

                // Identification required if nominee = true
                String isNominee = getCell(row, columnMap.get("Is Nominee"));
                String identification = getCell(row, columnMap.get("Identification"));
                if ("true".equalsIgnoreCase(isNominee) && identification.isEmpty()) {
                    logError(rowNum, "'Identification' is required when 'Is Nominee' is true (No. " + memberNo + ")");
                }
            }

            // Allocation total check
            double total = allocations.stream().mapToDouble(Double::doubleValue).sum();
            if (total > 100.0) {
                logError(0, "'Allocation' total exceeds 100%: " + total);
            }

        } catch (Exception e) {
            logError(0, "Error processing nominee Excel file: " + e.getMessage());
        }

        return rowErrors;
    }

    // === Validation Helpers ===

    private void validateBoolean(String field, Row row, Map<String, Integer> map, int rowNum, String no) {
        String value = getCell(row, map.get(field));
        if (!value.isEmpty() && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            logError(rowNum, "'" + field + "' must be true or false (No. " + no + ")");
        }
    }

    private void validatePhone(Row row, Integer colIndex, int rowNum, String no) {
        String value = getCell(row, colIndex);
        if (!value.isEmpty() && !value.matches("^\\+?\\d{9,15}$")) {
            logError(rowNum, "'Mobile No' format is invalid (No. " + no + ")");
        }
    }

    private void validateEmail(Row row, Integer colIndex, int rowNum, String no) {
        String value = getCell(row, colIndex);
        if (!value.isEmpty() && !value.matches("^[\\w.-]+@[\\w.-]+\\.\\w+$")) {
            logError(rowNum, "'Email' format is invalid (No. " + no + ")");
        }
    }

    private void validateNumeric(String field, Row row, Map<String, Integer> map, int rowNum, String no) {
        String value = getCell(row, map.get(field));
        if (!value.isEmpty()) {
            try {
                Double.parseDouble(value);
            } catch (NumberFormatException e) {
                logError(rowNum, "'" + field + "' must be numeric (No. " + no + ")");
            }
        }
    }

    private void validateDate(Cell cell, int rowNum, String no) {
        if (cell == null) return;

        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return;
            }
            if (cell.getCellType() == CellType.STRING) {
                String val = cell.getStringCellValue().trim();
                new SimpleDateFormat("yyyy-MM-dd").parse(val);
            }
        } catch (Exception e) {
            logError(rowNum, "'Date of Birth' is not a valid date (No. " + no + ")");
        }
    }

    private String getCell(Row row, Integer index) {
        if (index == null) return "";
        Cell cell = row.getCell(index);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? new SimpleDateFormat("yyyy-MM-dd").format(cell.getDateCellValue())
                    : String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private void logError(int rowNum, String message) {
        rowErrors.computeIfAbsent(rowNum, k -> new ArrayList<>()).add(message);
        LogStorage.addLog("error", "Row " + rowNum + ": " + message);
    }
}