package com.databridge.validator;

import com.databridge.util.LogStorage;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Pattern;

public class StandingOrderLinesExcelValidator {

    private static final Logger logger = LoggerFactory.getLogger(StandingOrderLinesExcelValidator.class);
    private static final Map<Integer, List<String>> rowErrors = new HashMap<>();

    private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");

    private static final Set<String> VALID_DEST_ACCOUNT_TYPES = Set.of("Credit", "Loan");

    public Map<Integer, List<String>> validate(InputStream inputStream) throws Exception {
        rowErrors.clear();

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            // Skip header
            if (rowIterator.hasNext()) rowIterator.next();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                int rowNum = row.getRowNum();

                String documentNo = getStringValue(row.getCell(0));
                logInfo("Validating row: " + rowNum + " — Document No: " + documentNo);

                validateRequired(documentNo, rowNum, "Document No.");
                validateFromSet(getStringValue(row.getCell(1)), rowNum, "Destination Account Type2", VALID_DEST_ACCOUNT_TYPES);
                validateRequiredAlphanumeric(row.getCell(2), rowNum, "Destination Account No.");
                validateOptionalAlphanumeric(row.getCell(3), rowNum, "Loan No.");
                validateRequired(getStringValue(row.getCell(4)), rowNum, "Destination Account Name");
                validateAmount(row.getCell(5), rowNum, "Amount");
                validateOptionalAlphanumeric(row.getCell(6), rowNum, "Bank Code");
                validateOptionalAlphaOrAlphanumeric(row.getCell(7), rowNum, "Bank Name");
                validateOptionalAlphanumeric(row.getCell(8), rowNum, "External Bank Account No");
                validateRequiredAlphanumeric(row.getCell(9), rowNum, "Member No");
            }
        }

        if (!rowErrors.isEmpty()) {
            for (Map.Entry<Integer, List<String>> entry : rowErrors.entrySet()) {
                for (String error : entry.getValue()) {
                    logger.error("Row {}: {}", entry.getKey(), error);
                }
            }
        } else {
            logger.info("Standing Order Lines validation passed with no errors.");
        }

        return rowErrors;
    }

    private static void validateFromSet(String value, int rowNum, String field, Set<String> validValues) {
        if (!validValues.contains(value)) {
            logError(rowNum, "'" + field + "' has invalid value: " + value);
        }
    }

    private static void validateRequired(String value, int rowNum, String field) {
        if (value == null || value.trim().isEmpty()) {
            logError(rowNum, "'" + field + "' is required.");
        }
    }

    private static void validateRequiredAlphanumeric(Cell cell, int rowNum, String field) {
        String value = getStringValue(cell);
        if (value.isEmpty() || !ALPHANUMERIC_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'" + field + "' is required and must be alphanumeric.");
        }
    }

    private static void validateOptionalAlphanumeric(Cell cell, int rowNum, String field) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !ALPHANUMERIC_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'" + field + "' must be alphanumeric if provided.");
        }
    }

    private static void validateOptionalAlphaOrAlphanumeric(Cell cell, int rowNum, String field) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !value.matches("^[a-zA-Z0-9\\s.\\-()]+$")) {
            logError(rowNum, "'" + field + "' contains invalid characters.");
        }
    }

    private static void validateAmount(Cell cell, int rowNum, String field) {
        String value = getStringValue(cell);
        try {
            double amount = Double.parseDouble(value);
            if (amount <= 0) {
                logError(rowNum, "'" + field + "' must be greater than zero.");
            }
        } catch (NumberFormatException e) {
            logError(rowNum, "'" + field + "' must be a valid number.");
        }
    }

    private static String getStringValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return new java.text.SimpleDateFormat("yyyy-MM-dd").format(cell.getDateCellValue());
                }
                return new DecimalFormat("0").format(cell.getNumericCellValue());
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try { return cell.getStringCellValue().trim(); }
                catch (IllegalStateException e) {
                    return new DecimalFormat("0").format(cell.getNumericCellValue());
                }
            default: return "";
        }
    }

    private static void logError(int rowNum, String message) {
        rowErrors.computeIfAbsent(rowNum, k -> new ArrayList<>()).add(message);
        LogStorage.addLog("error", "Row " + rowNum + ": " + message);
    }

    private static void logInfo(String message) {
        LogStorage.addLog("info", message);
    }
}