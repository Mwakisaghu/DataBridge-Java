package com.databridge.validator;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Pattern;

public class CustomerExcelValidator {

    private static final Logger logger = LoggerFactory.getLogger(CustomerExcelValidator.class);
    private static final Map<Integer, List<String>> rowErrors = new HashMap<>();

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$");
    private static final Pattern KRA_PIN_PATTERN = Pattern.compile("^[A-Z][0-9]{9}[A-Z]$");
    private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9\\s\\-]+$");

    public Map<Integer, List<String>> validate(InputStream inputStream) throws Exception {
        rowErrors.clear();

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            // Skip header row
            if (rowIterator.hasNext()) rowIterator.next();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                int rowNum = row.getRowNum();
                String customerNo = getStringValue(row.getCell(0));

                logger.info("Validating row {} — Customer No: {}", rowNum, customerNo);

                validateRequired(row.getCell(0), rowNum, "Customer No.");
                validateRequired(row.getCell(1), rowNum, "Name");
                validateOptionalAlphanumeric(row.getCell(2), rowNum, "Address");
                validatePhoneNo(row.getCell(3), rowNum);
                validateNumeric(row.getCell(4), rowNum, "Credit Limit (LCY)");
                validateRequired(row.getCell(5), rowNum, "Customer Posting Group");
                validateBoolean(row.getCell(6), rowNum, "Prices Including VAT");
                validateRequired(row.getCell(7), rowNum, "Gen. Bus. Posting Group");
                validateEmail(row.getCell(8), rowNum);
                validateBoolean(row.getCell(9), rowNum, "Tax Liable");
                validateRequired(row.getCell(10), rowNum, "VAT Bus. Posting Group");
                validateOptionalAlphanumeric(row.getCell(11), rowNum, "Account Type");
                validateOptionalAlphanumeric(row.getCell(12), rowNum, "Employee Job Group");
                validateRequired(row.getCell(13), rowNum, "Status");
                validateKraPin(row.getCell(14), rowNum);
                validateOptionalAlphanumeric(row.getCell(15), rowNum, "Old No.");
            }

            if (!rowErrors.isEmpty()) {
                logger.info("Validation completed with errors:");
                rowErrors.forEach((row, errors) -> errors.forEach(e -> logger.error("Row {}: {}", row, e)));
            } else {
                logger.info("Validation completed successfully. No errors found.");
            }

        } catch (Exception e) {
            logger.error("Error during customer data validation", e);
        }

        return rowErrors;
    }

    private static void logError(int rowNum, String message) {
        rowErrors.computeIfAbsent(rowNum, k -> new ArrayList<>()).add(message);
    }

    private static String getStringValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC: return new DecimalFormat("0").format(cell.getNumericCellValue());
            case BOOLEAN: return Boolean.toString(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {
                    return new DecimalFormat("0").format(cell.getNumericCellValue());
                }
            default: return "";
        }
    }

    // Individual column validators
    private void validateRequired(Cell cell, int rowNum, String fieldName) {
        if (cell == null || getStringValue(cell).isEmpty()) {
            logError(rowNum, "'" + fieldName + "' is required.");
        }
    }

    private void validateOptionalAlphanumeric(Cell cell, int rowNum, String fieldName) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !ALPHANUMERIC_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'" + fieldName + "' must be alphanumeric.");
        }
    }

    private void validatePhoneNo(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !value.matches("^(\\+254|254|0)?[17]\\d{8}$")) {
            logError(rowNum, "'Phone No.' is invalid. Expected formats: 07XXXXXXXX, +2547XXXXXXX.");
        }
    }

    private void validateNumeric(Cell cell, int rowNum, String fieldName) {
        String value = getStringValue(cell);
        try {
            if (!value.isEmpty() && Double.parseDouble(value) < 0) {
                logError(rowNum, "'" + fieldName + "' cannot be negative.");
            }
        } catch (NumberFormatException e) {
            logError(rowNum, "'" + fieldName + "' must be a numeric value.");
        }
    }

    private void validateBoolean(Cell cell, int rowNum, String fieldName) {
        String value = getStringValue(cell).toLowerCase();
        if (!value.equals("true") && !value.equals("false")) {
            logError(rowNum, "'" + fieldName + "' must be 'true' or 'false'.");
        }
    }

    private void validateEmail(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !EMAIL_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'Email' format is invalid.");
        }
    }

    private void validateKraPin(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !KRA_PIN_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'KRA PIN' format is invalid. Expected e.g. A123456789B.");
        }
    }
}