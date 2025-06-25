package com.databridge.validator;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Pattern;

public class BankAccountExcelValidator {

    private static final Logger logger = LoggerFactory.getLogger(BankAccountExcelValidator.class);
    private static final Map<Integer, List<String>> rowErrors = new HashMap<>();
    private static final Set<String> seenBankAccountNumbers = new HashSet<>();

    private static final Pattern KENYAN_PHONE_PATTERN = Pattern.compile("^(\\+254|254|0)?[17]\\d{8}$");
    private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9\\s\\-\\/\\.]+$");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    private static final Set<String> VALID_ACTIVITIES = Set.of("FOSA", "BOSA");
    private static final Set<String> VALID_BANK_TYPES = Set.of(
            "Normal", "Cash", "Fixed Deposit", "Chq Collection", "Cashier", "Treasury", "Control", "Mobile Money"
    );
    private static final Set<String> VALID_BLOCKED_VALUES = Set.of("", "true", "false");

    public Map<Integer, List<String>> validate(InputStream inputStream) throws Exception {
        rowErrors.clear();
        seenBankAccountNumbers.clear();

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            if (rowIterator.hasNext()) rowIterator.next();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                int rowNum = row.getRowNum();
                String accountNo = getStringValue(row.getCell(0));
                logger.info("Validating row {} — Bank Account No: {}", rowNum, accountNo);

                validateAccountNo(row.getCell(0), rowNum);
                validateRequired(row.getCell(1), rowNum, "Name");
                validatePhoneNo(row.getCell(2), rowNum);
                validateOptionalAlphanumeric(row.getCell(3), rowNum, "Bank Account No.");
                validateOptionalAlphanumeric(row.getCell(4), rowNum, "Branch Code");
                validateActivity(row.getCell(5), rowNum);
                validateRequired(row.getCell(6), rowNum, "Bank Acc. Posting Group");
                validateBlocked(row.getCell(7), rowNum);
                validateOptionalAlphanumeric(row.getCell(8), rowNum, "County");
                validateOptionalAlphanumeric(row.getCell(9), rowNum, "SWIFT Code");
                validateBankType(row.getCell(10), rowNum);
                validateCurrencyCode(row.getCell(11), rowNum);
            }

            if (!rowErrors.isEmpty()) {
                logger.info("Validation completed with errors.");
                rowErrors.forEach((row, errors) -> errors.forEach(e -> logger.error("Row {}: {}", row, e)));
            } else {
                logger.info("Validation completed successfully. No errors found.");
            }

        } catch (Exception e) {
            logger.error("Error validating bank account Excel file", e);
        }

        return rowErrors;
    }

    // Validation Methods

    private void validateAccountNo(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (value.isEmpty()) {
            logError(rowNum, "'No.' is required.");
        } else if (!ALPHANUMERIC_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'No.' must be alphanumeric.");
        } else if (!seenBankAccountNumbers.add(value)) {
            logError(rowNum, "'No.' is duplicated: " + value);
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
            logError(rowNum, "'Phone No.' format is invalid.");
        }
    }

    private void validateActivity(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!VALID_ACTIVITIES.contains(value)) {
            logError(rowNum, "'Activity' must be FOSA or BOSA. Found: " + value);
        }
    }

    private void validateBlocked(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!VALID_BLOCKED_VALUES.contains(value)) {
            logError(rowNum, "'Blocked' must be blank, 'true' or 'false'. Found: " + value);
        }
    }

    private void validateBankType(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !VALID_BANK_TYPES.contains(value)) {
            logError(rowNum, "'Bank Type' is invalid. Allowed: " + VALID_BANK_TYPES);
        }
    }

    private void validateCurrencyCode(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !CURRENCY_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'Currency Code' must be a 3-letter uppercase code. Found: " + value);
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