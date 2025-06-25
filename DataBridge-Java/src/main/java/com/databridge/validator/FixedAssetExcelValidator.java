package com.databridge.validator;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Pattern;

public class FixedAssetExcelValidator {

    private static final Logger logger = LoggerFactory.getLogger(FixedAssetExcelValidator.class);
    private static final Map<Integer, List<String>> rowErrors = new HashMap<>();
    private static final Set<String> seenAssetNumbers = new HashSet<>();

    private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9\\s\\-\\/\\.]+$");

    private static final Set<String> VALID_ACTIVITY_CODES = Set.of("FOSA", "BOSA");
    private static final Set<String> VALID_RESPONSIBILITY_CENTERS = Set.of("FOSA", "BOSA", "HQ", "ICT", "FINANCE", "HR", "PROC");

    private static final Set<String> VALID_VEHICLE_STATUSES = Set.of("Active", "Inactive");
    private static final Set<String> VALID_ASSET_STATUSES = Set.of("Good working condition", "Obsolete", "Broken");
    private static final Set<String> VALID_FIXED_ASSET_STATUSES = Set.of(
            "Open", "Approved", "Pending Approval", "Deffered", "Cancelled", "Rejected", "Closed"
    );
    private static final Set<String> KNOWN_VEHICLE_MAKES = Set.of(
            "Toyota", "Nissan", "Mitsubishi", "Isuzu", "Mercedez Benz", "BMW", "Volkswagen", "Ford"
    );

    private static final Set<String> VALID_BOOLEAN_TEXT = Set.of("true", "false", "");

    public Map<Integer, List<String>> validate(InputStream inputStream) throws Exception {
        rowErrors.clear();
        seenAssetNumbers.clear();

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            if (rowIterator.hasNext()) rowIterator.next(); // Skip header

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                int rowNum = row.getRowNum();
                String assetNo = getStringValue(row.getCell(0));
                logger.info("Validating row {} — Fixed Asset No: {}", rowNum, assetNo);

                validateAssetNo(row.getCell(0), rowNum);
                validateRequired(row.getCell(1), rowNum, "Description");
                validateRequired(row.getCell(2), rowNum, "FA Class Code");
                validateOptionalAlphanumeric(row.getCell(3), rowNum, "FA Subclass Code");
                validateOptionalAlphanumeric(row.getCell(4), rowNum, "Branch Code");
                validateActivityCode(row.getCell(5), rowNum);
                validateOptionalAlphanumeric(row.getCell(6), rowNum, "Serial No.");
                validateRequired(row.getCell(7), rowNum, "FA Posting Group");
                validateOptionalAlphanumeric(row.getCell(8), rowNum, "Vehicle Registration No.");
                validateOptionalAlphanumeric(row.getCell(9), rowNum, "Registration No.");
                validateResponsibilityCenter(row.getCell(10), rowNum);
                validateVehicleStatus(row.getCell(11), rowNum);
                validateStatus(row.getCell(12), rowNum);
                validateVehicleMake(row.getCell(13), rowNum);
                validateOptionalAlphanumeric(row.getCell(14), rowNum, "Asset Tag");
                validateOptionalAlphanumeric(row.getCell(15), rowNum, "REG No.");
                validateOptionalAlphanumeric(row.getCell(16), rowNum, "Responsible Employee");
                validateAssetStatus(row.getCell(17), rowNum);
                validateMarkedForDisposal(row.getCell(18), rowNum);
            }

            if (!rowErrors.isEmpty()) {
                logger.info("Validation completed with errors.");
                rowErrors.forEach((row, errors) -> errors.forEach(e -> logger.error("Row {}: {}", row, e)));
            } else {
                logger.info("Validation completed successfully. No errors found.");
            }

        } catch (Exception e) {
            logger.error("Error validating fixed asset Excel file", e);
        }

        return rowErrors;
    }

    // Validation Methods

    private void validateAssetNo(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (value.isEmpty()) {
            logError(rowNum, "'No.' is required.");
        } else if (!ALPHANUMERIC_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'No.' must be alphanumeric.");
        } else if (!seenAssetNumbers.add(value)) {
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

    private void validateActivityCode(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!VALID_ACTIVITY_CODES.contains(value)) {
            logError(rowNum, "'Activity Code' must be FOSA or BOSA. Found: " + value);
        }
    }

    private void validateResponsibilityCenter(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !VALID_RESPONSIBILITY_CENTERS.contains(value)) {
            logError(rowNum, "'Responsibility Center' is invalid. Found: " + value);
        }
    }

    private void validateVehicleStatus(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !VALID_VEHICLE_STATUSES.contains(value)) {
            logError(rowNum, "'Vehicle Status' must be 'Active' or 'Inactive'. Found: " + value);
        }
    }

    private void validateStatus(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !VALID_FIXED_ASSET_STATUSES.contains(value)) {
            logError(rowNum, "'Status' is invalid. Allowed: " + VALID_FIXED_ASSET_STATUSES);
        }
    }

    private void validateVehicleMake(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !ALPHANUMERIC_PATTERN.matcher(value).matches()) {
            logError(rowNum, "'Vehicle Make' contains invalid characters.");
        } else if (!value.isEmpty() && !KNOWN_VEHICLE_MAKES.contains(value)) {
            logger.warn("Row {}: 'Vehicle Make' [{}] is not in the known list but allowed.", rowNum, value);
        }
    }

    private void validateAssetStatus(Cell cell, int rowNum) {
        String value = getStringValue(cell);
        if (!value.isEmpty() && !VALID_ASSET_STATUSES.contains(value)) {
            logError(rowNum, "'Asset Status' is invalid. Allowed: " + VALID_ASSET_STATUSES);
        }
    }

    private void validateMarkedForDisposal(Cell cell, int rowNum) {
        String value = getStringValue(cell).toLowerCase();
        if (!VALID_BOOLEAN_TEXT.contains(value)) {
            logError(rowNum, "'Marked for Disposal' must be 'true', 'false', or blank.");
        }
    }

    // Utilities

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