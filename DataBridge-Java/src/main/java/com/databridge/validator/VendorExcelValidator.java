package com.databridge.validator;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Pattern;

public class VendorExcelValidator {

    private static final Logger logger = LoggerFactory.getLogger(VendorExcelValidator.class);
    private static final Map<Integer, List<String>> rowErrors = new HashMap<>();
    private static final Set<String> seenVendorNumbers = new HashSet<>();

    private static final Pattern KRA_PIN_PATTERN = Pattern.compile("^[A-Z][0-9]{9}[A-Z]$");
    private static final Pattern KENYAN_PHONE_PATTERN = Pattern.compile("^(\\+254|254|0)?[17]\\d{8}$");
    private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9\\s\\-\\/\\.]+$");

    private static final Set<String> VALID_ACTIVITY_CODES = Set.of("FOSA", "BOSA");
    private static final Set<String> VALID_BLOCKED_VALUES = Set.of("", "Payment", "All");
    private static final Set<String> VALID_STATUSES = Set.of("New", "Pending Approval", "Approved", "Rejected");

    public Map<Integer, List<String>> validate(InputStream inputStream) throws Exception {
        rowErrors.clear();
        seenVendorNumbers.clear();

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            if (rowIterator.hasNext()) rowIterator.next();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                int rowNum = row.getRowNum();
                String vendorNo = getStringValue(row.getCell(0));
                logger.info("Validating row {} — Vendor No: {}", rowNum, vendorNo);

                validateVendorNumber(row.getCell(0), rowNum);
                validateRequired(row.getCell(1), rowNum, "Name");
                validateOptionalAlphanumeric(row.getCell(2), rowNum, "Address");
                validatePhoneNo(row.getCell(3), rowNum);
                validateOptionalAlphanumeric(row.getCell(4), rowNum, "Branch Code");
                validateActivityCode(row.getCell(5), rowNum);
                validateRequired(row.getCell(6), rowNum, "Vendor Posting Group");
                validateBlocked(row.getCell(7), rowNum);
                validateStatus(row.getCell(8), rowNum);
                validateNumeric(row.getCell(9), rowNum, "Vendor Credit Limit (LCY)");
                validateKraPin(row.getCell(10), rowNum);
                validateOptionalAlphanumeric(row.getCell(11), rowNum, "P.O.Box Number");
                validateOptionalAlphanumeric(row.getCell(12), rowNum, "Physical Address");
                validateOptionalAlphanumeric(row.getCell(13), rowNum, "Vendor Type");
            }

            if (!rowErrors.isEmpty()) {
                logger.info("Validation completed with errors.");
                rowErrors.forEach((row, errors) -> errors.forEach(e -> logger.error("Row {}: {}", row, e)));
            } else {
                logger.info("Validation completed successfully. No errors found.");
            }

        } catch (Exception e) {
            logger.error("Error validating vendor Excel file", e);
        }

        return rowErrors;
    }

    // Validation Methods 

    private void validateVendorNumber(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (value.isEmpty()) {
            logError(rowNum, "'Vendor No.' is required.");
        } else if (!ALPHANUMERIC_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'Vendor No.' must be alphanumeric.");
        } else if (!seenVendorNumbers.add(value)) {
            logError(rowNum, "'Vendor No.' is duplicated: " + value);
        }
    }

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
        if (!value.isEmpty() && !KENYAN_PHONE_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'Phone No.' is invalid. Expected Kenyan format.");
        }
    }

    private void validateActivityCode(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !VALID_ACTIVITY_CODES.contains(value)) {
            logError(rowNum, "'Activity Code' must be 'FOSA' or 'BOSA'. Found: " + value);
        }
    }

    private void validateBlocked(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!VALID_BLOCKED_VALUES.contains(value)) {
            logError(rowNum, "'Blocked' must be blank, 'Payment' or 'All'. Found: " + value);
        }
    }

    private void validateStatus(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!VALID_STATUSES.contains(value)) {
            logError(rowNum, "'Status' is invalid. Allowed: " + VALID_STATUSES);
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

    private void validateKraPin(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !KRA_PIN_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'KRA PIN No.' format is invalid. Expected e.g. A123456789B.");
        }
    }

    // Utility Methods

    private static String getStringValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                return new DecimalFormat("0").format(cell.getNumericCellValue());
            case BOOLEAN:
                return Boolean.toString(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {
                    return new DecimalFormat("0").format(cell.getNumericCellValue());
                }
            default:
                return "";
        }
    }

    private static void logError(int rowNum, String message) {
        rowErrors.computeIfAbsent(rowNum, k -> new ArrayList<>()).add(message);
    }
}