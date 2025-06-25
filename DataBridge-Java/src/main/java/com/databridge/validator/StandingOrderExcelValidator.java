package com.databridge.validator;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

public class StandingOrderExcelValidator {

    private static final Logger logger = LoggerFactory.getLogger(StandingOrderExcelValidator.class);
    private static final Map<Integer, List<String>> rowErrors = new HashMap<>();
    private static final Set<String> seenStandingOrderNos = new HashSet<>();

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9\\s\\-\\/\\.]+$");
    private static final Pattern DURATION_PATTERN = Pattern.compile("^\\d+[mM]$");

    private static final Set<String> VALID_STATUSES = Set.of("Open", "Pending", "Approved", "Rejected", "Stopped");
    private static final Set<String> VALID_SOURCE_ACCOUNT_TYPES = Set.of("Savings");
    private static final Set<String> VALID_STO_TYPES = Set.of("Internal", "External", "Pensioner");
    private static final Set<String> VALID_INCOME_TYPES = Set.of("Salary", "Direct Debit", "SAYE", "Periodic", "Pension");
    private static final Set<String> VALID_ACTIVITY_CODES = Set.of("FOSA", "BOSA");
    private static final Set<String> BOOLEAN_TEXTS = Set.of("true", "false", "");

    public Map<Integer, List<String>> validate(InputStream inputStream) {
        rowErrors.clear();
        seenStandingOrderNos.clear();

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            if (rowIterator.hasNext()) rowIterator.next();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                int rowNum = row.getRowNum();
                String orderNo = getStringValue(row.getCell(0));

                logger.info("Validating row {} — Standing Order No: {}", rowNum, orderNo);

                validateRequiredUniqueNo(row.getCell(0), rowNum);
                validateDate(row.getCell(1), rowNum, "Application Date");
                validateEnum(row.getCell(2), rowNum, "Status", VALID_STATUSES);
                validateEnum(row.getCell(3), rowNum, "Source Account Type", VALID_SOURCE_ACCOUNT_TYPES);
                validateRequired(row.getCell(4), rowNum, "Source Account No.");
                validateRequired(row.getCell(5), rowNum, "Source Account Name");
                validateRequired(row.getCell(6), rowNum, "Member No.");
                validateRequired(row.getCell(7), rowNum, "Description");
                validateNumeric(row.getCell(8), rowNum, "Amount");
                validateEnum(row.getCell(9), rowNum, "Standing Order Type", VALID_STO_TYPES);
                validateBoolean(row.getCell(10), rowNum, "Allow Partial Deduction");
                validateEnum(row.getCell(11), rowNum, "Income Type", VALID_INCOME_TYPES);
                validateDate(row.getCell(12), rowNum, "Effective/Start Date");
                validateDuration(row.getCell(13), rowNum, "Frequency (Months)");
                validateDuration(row.getCell(14), rowNum, "Duration (Months)");
                validateDate(row.getCell(15), rowNum, "End Date");
                validateDate(row.getCell(16), rowNum, "Next Run Date");
                validateBoolean(row.getCell(17), rowNum, "Unrecovered");
                validateEnum(row.getCell(18), rowNum, "Transaction Type", Set.of("STO", ""));
                validateOptionalAlphanumeric(row.getCell(19), rowNum, "Branch Code");
                validateEnum(row.getCell(20), rowNum, "Activity Code", VALID_ACTIVITY_CODES);
                validateDate(row.getCell(21), rowNum, "Date Stopped");
                validateNumeric(row.getCell(22), rowNum, "Target Amount");
            }

        } catch (Exception e) {
            logger.error("Error validating standing order file", e);
        }

        return rowErrors;
    }

    // Validation Methods

    private void validateRequiredUniqueNo(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (value.isEmpty()) {
            logError(rowNum, "'No.' is required.");
        } else if (!ALPHANUMERIC_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'No.' must be alphanumeric.");
        } else if (!seenStandingOrderNos.add(value)) {
            logError(rowNum, "'No.' is duplicated: " + value);
        }
    }

    private void validateRequired(Cell cell, int rowNum, String field) {
        if (getStringValue(cell).isEmpty()) {
            logError(rowNum, "'" + field + "' is required.");
        }
    }

    private void validateEnum(Cell cell, int rowNum, String field, Set<String> allowed) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !allowed.contains(value)) {
            logError(rowNum, "'" + field + "' has invalid value: " + value);
        }
    }

    private void validateBoolean(Cell cell, int rowNum, String field) {
        String value = getStringValue(cell).toLowerCase();
        if (!BOOLEAN_TEXTS.contains(value)) {
            logError(rowNum, "'" + field + "' must be 'true', 'false', or blank. Found: " + value);
        }
    }

    private void validateDuration(Cell cell, int rowNum, String field) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !DURATION_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'" + field + "' must be in format like '1M', '6M'. Found: " + value);
        }
    }

    private void validateDate(Cell cell, int rowNum, String field) {
        String value = getStringValue(cell);
        if (!value.isEmpty()) {
            try {
                DATE_FORMAT.parse(value);
            } catch (Exception e) {
                logError(rowNum, "'" + field + "' must be a valid date (yyyy-MM-dd). Found: " + value);
            }
        }
    }

    private void validateNumeric(Cell cell, int rowNum, String field) {
        String value = getStringValue(cell);
        try {
            if (!value.isEmpty()) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logError(rowNum, "'" + field + "' must be numeric. Found: " + value);
        }
    }

    private void validateOptionalAlphanumeric(Cell cell, int rowNum, String field) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !ALPHANUMERIC_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'" + field + "' must be alphanumeric.");
        }
    }

    // Utilities

    private static String getStringValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return DATE_FORMAT.format(cell.getDateCellValue());
                }
                return new DecimalFormat("0.##").format(cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
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