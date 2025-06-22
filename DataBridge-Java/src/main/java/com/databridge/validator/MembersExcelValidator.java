package com.databridge.validator;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.databridge.util.LogStorage;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

public class MembersExcelValidator {

    private static final Set<String> seenMemberNumbers = new HashSet<>();

    private static final Logger logger = LoggerFactory.getLogger(MembersExcelValidator.class);
    private static final Map<Integer, List<String>> rowErrors = new HashMap<>();

    private static final String[] VALID_STATUS = {"Open", "New", "Active", "Dormant", "Deceased", "Closed"};
    private static final String[] VALID_SUB_STATUS = {
        "Pending/Deferred", "First time", "Rejoined", "Partial withdrawn",
        "Suspended", "Expelled", "Defaulter", "Withdrawal", "Pending Risk Claim"
    };
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    private static final String[] VALID_TERMS_OF_EMPLOYMENT = {
        "Permanent and Pensionable", "Contract", "Casual"
    };
    private static final String[] VALID_MARITAL_STATUS = {
        "Single", "Married", "Divorced", "Widower", "Widow", "Others"
    };
    private static final String[] VALID_GENDERS = {
        "Male", "Female", "Others"
    };
    private static final String[] VALID_MEMBER_CATEGORIES = {
        "Member", "Staff Member", "Board Member", "Delegate"
    };

    private static final String[] VALID_APPLICATION_TYPES = {
    "New Registration", "Readmission", "Nominee"
    };

    private static final String[] VALID_ACCOUNT_TYPES = {
        "Single Member Account", "Business Loan Account", "Chama Investment"
    };

    private static final String[] VALID_ACCOUNT_PROFILES = {
        "Normal Member", "High Profile", "Politically Exposed"
    };

    private static final String[] VALID_ID_TYPES = {
    "ID Number", "Passport No", "Service Number", 
    "Birth Certificate Number", "Huduma/Maisha Number", "System UID"
    };

    private static final Set<String> VALID_RESPONSIBILITY_CENTERS = Set.of(
    "FOSA", "BOSA", "HR", "ICT", "PROC", "DMU", "RECORDS", "REGISTRY", "FINANCE", "MEMBERSHIP"
);

    private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");
    private static final Pattern ALPHABETIC_PATTERN = Pattern.compile("^[a-zA-Z]+$");
    private static final Pattern KENYAN_PHONE_PATTERN = Pattern.compile("^(07|01|2547|2541)\\d{8}$");
    
    public Map<Integer, List<String>> validate(InputStream inputStream) throws Exception {
        //Map<Integer, List<String>> rowErrors = new HashMap<>();

        // Clear previous errors
        rowErrors.clear();
        seenMemberNumbers.clear(); 

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            // Skip header row
            if (rowIterator.hasNext()) rowIterator.next();

        while (rowIterator.hasNext()) {
            Row row = rowIterator.next();
            int rowNum = row.getRowNum();
            String noValue = getStringValue(row.getCell(0));

            System.out.println("Validating row: " + rowNum + " — Member No: " + noValue);

            validateMemberNoColumn(row.getCell(0), rowNum, noValue);
            validateSalutationColumn(row.getCell(1), rowNum, noValue);
            validateNameColumn(row.getCell(2), rowNum, noValue);
            validateFirstNameColumn(row.getCell(3), rowNum, noValue);
            validateLastNameColumn(row.getCell(4), rowNum, noValue);
            validateOtherNamesColumn(row.getCell(5), rowNum, noValue);
            validateCurrentAddressColumn(row.getCell(6), rowNum, noValue);
            validateHomeAddressColumn(row.getCell(7), rowNum, noValue);
            validatePhoneNoColumn(row.getCell(8), rowNum, noValue);
            validateBranchCodeColumn(row.getCell(9), rowNum, noValue);
            validateActivityCodeColumn(row.getCell(10), rowNum, noValue);
            validateNationalityColumn(row.getCell(11), rowNum, noValue);
            validatePostCodeColumn(row.getCell(12), rowNum, noValue);
            validateEmailColumn(row.getCell(13), rowNum, noValue);
            validateResponsibilityCenterColumn(row.getCell(14), rowNum, noValue);

            try {
                validateRegistrationDate(row.getCell(15), rowNum, noValue);
            } catch (java.text.ParseException e) {
                logError(rowNum, "'Registration Date' parsing error (No. " + noValue + ")");

            validateStatus(row.getCell(16), rowNum, noValue);
            validateSubStatus(row.getCell(17), rowNum, noValue);
            validateEmployerCode(row.getCell(18), rowNum, noValue);
            validateLocation(row.getCell(19), rowNum, noValue, "Location");
            validateTermsOfEmployment(row.getCell(20), rowNum, noValue);
            validateWorkStation(row.getCell(21), rowNum, noValue);
            validateCountryOfOperation(row.getCell(22), rowNum, noValue);
            validateEmploymentDesignation(row.getCell(23), rowNum, noValue);
            validateCity(row.getCell(24), rowNum, noValue);
            validateDateOfBirth(row.getCell(25), rowNum, noValue);
            validatePayrollStaffNo(row.getCell(26), rowNum, noValue);
            validateMaritalStatus(row.getCell(27), rowNum, noValue);
            validateGender(row.getCell(28), rowNum, noValue);
            validateMemberCategory(row.getCell(29), rowNum, noValue);
            validateGroupAccountNo(row.getCell(30), rowNum, noValue);
            validateTypeOfApplication(row.getCell(31), rowNum, noValue);
            validateMemberClass(row.getCell(32), rowNum, noValue);
            validateAccountType(row.getCell(33), rowNum, noValue);
            validateAccountProfile(row.getCell(34), rowNum, noValue);
            validateReligion(row.getCell(35), rowNum, noValue);
            validatePINNumber(row.getCell(36), rowNum, noValue);
            validateRejoiningDate(row.getCell(37), rowNum, noValue);
            validatePrimaryIdentificationType(row.getCell(38), rowNum, noValue);
            validateSecondaryIdentificationType(row.getCell(39), rowNum, noValue);
            validatePrimaryIdentification(row.getCell(40), rowNum, noValue);
            validateSecondaryIdentification(row.getCell(41), rowNum, noValue);
            validateFromAnotherSacco(row.getCell(42), rowNum, noValue);
            validateOptionalAlphanumeric(row.getCell(43), rowNum, "Old Member No.", noValue);
            validateOptionalAlphanumeric(row.getCell(44), rowNum, "Principal Member", noValue);
            validateOptionalAlphanumeric(row.getCell(45), rowNum, "Salesperson Code", noValue);
            validateOptionalAlphaOrAlphanumeric(row.getCell(46), rowNum, "Recruited By Name", noValue);
            validateOptionalAlphaOrAlphanumeric(row.getCell(47), rowNum, "Recruited By Type", noValue);
            validateHasJoinedBusinessLoans(row.getCell(48), rowNum, noValue);
            validateOptionalAlphabetic(row.getCell(49), rowNum, "Electoral Zone Descr.", noValue);
            validateOptionalAlphanumeric(row.getCell(50), rowNum, "CRM Application No.", noValue);
            validateContributionStartDate(row.getCell(51), rowNum, noValue);
            validateTransactionalMobileNumber(row.getCell(52), rowNum, noValue);
            validateAge(getStringValue(row.getCell(53)));
            validatePayrollNumber(getStringValue(row.getCell(54)));
            validateCompanyRegistrationNo(getStringValue(row.getCell(55)));
            validateOptionalAlphaOrAlphanumeric(row.getCell(56), rowNum, "Allocated Officer", noValue);
            validateOptionalAlphaOrAlphanumeric(row.getCell(57), rowNum, "Allocated Officer Name", noValue);
            validateContributionEffectiveDate(getStringValue(row.getCell(58)));
            validateOptionalAlphabetic(row.getCell(59), rowNum, "Delegates Zone", noValue);
        }

     catch (Exception e) {
        e.printStackTrace();
    }
}


            // Report collected errors
            if (!rowErrors.isEmpty()) {
                logger.info("Validation completed with errors:");
                for (Map.Entry<Integer, List<String>> entry : rowErrors.entrySet()) {
                    for (String error : entry.getValue()) {
                        logger.error("Row {}: {}", entry.getKey(), error);
                    }
                }
            } else {
                logger.info("Validation completed successfully. No errors found.");
            }

        } catch (IOException e) {
            logger.error("Error reading Excel file", e);
        }
        return rowErrors;
    }

    private static String getStringValue(Cell cell) {
    if (cell == null) return "";

    switch (cell.getCellType()) {
        case STRING:
            return cell.getStringCellValue().trim();
        case NUMERIC:
            if (DateUtil.isCellDateFormatted(cell)) {
                return DATE_FORMAT.format(cell.getDateCellValue());
            }
            // Prevent scientific notation, and convert to plain string
            return new java.text.DecimalFormat("0").format(cell.getNumericCellValue());
        case BOOLEAN:
            return String.valueOf(cell.getBooleanCellValue());
        case FORMULA:
            try {
                return cell.getStringCellValue().trim();
            } catch (IllegalStateException e) {
                return new java.text.DecimalFormat("0").format(cell.getNumericCellValue());
            }
        default:
            return "";
    }
}

    private static void logError(int rowNum, String message) {
        rowErrors.computeIfAbsent(rowNum, k -> new ArrayList<>()).add(message);

        LogStorage.addLog("error", "Row " + rowNum + ": " + message);
    }

    // Validation methods
    public static boolean validateSalutationColumn(Cell cell, int rowNum, String noValue) {
        if (cell == null || cell.getCellType() != CellType.STRING || cell.getStringCellValue().trim().isEmpty()) {
            logError(rowNum, "'Salutation' is blank or invalid (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateNameColumn(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) {
            logError(rowNum, "'Name' is blank (No. " + noValue + ")");
            return false;
        }
        if (value.length() < 2 || value.length() > 100) {
            logError(rowNum, "'Name' must be 2-100 characters long (No. " + noValue + ")");
            return false;
        }
        if (!value.matches("^[A-Za-z ,.'-]+$")) {
            logError(rowNum, "'Name' contains invalid characters (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateFirstNameColumn(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) {
            logError(rowNum, "'First Name' is blank (No. " + noValue + ")");
            return false;
        }
        if (value.length() < 2 || value.length() > 50) {
            logError(rowNum, "'First Name' must be 2-50 characters long (No. " + noValue + ")");
            return false;
        }
        if (!value.matches("^[A-Za-z .'-]+$")) {
            logError(rowNum, "'First Name' contains invalid characters (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateLastNameColumn(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) {
            logError(rowNum, "'Last Name' is blank (No. " + noValue + ")");
            return false;
        }
        if (value.length() < 2 || value.length() > 50) {
            logError(rowNum, "'Last Name' must be 2-50 characters long (No. " + noValue + ")");
            return false;
        }
        if (!value.matches("^[A-Za-z .'-]+$")) {
            logError(rowNum, "'Last Name' contains invalid characters (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateOtherNamesColumn(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) return true;
        if (value.length() < 2 || value.length() > 100) {
            logError(rowNum, "'Other Names' must be 2-100 characters long (No. " + noValue + ")");
            return false;
        }
        if (!value.matches("^[A-Za-z .'-]+$")) {
            logError(rowNum, "'Other Names' contains invalid characters (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateCurrentAddressColumn(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) return true;
        if (value.length() < 5 || value.length() > 150) {
            logError(rowNum, "'Current Address' must be 5-150 characters (No. " + noValue + ")");
            return false;
        }
        if (!value.matches("^[A-Za-z0-9 ,./'-]+$")) {
            logError(rowNum, "'Current Address' contains invalid characters (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateHomeAddressColumn(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) return true;
        if (value.length() < 5 || value.length() > 150) {
            logError(rowNum, "'Home Address' must be 5-150 characters (No. " + noValue + ")");
            return false;
        }
        if (!value.matches("^[A-Za-z0-9 ,./'-]+$")) {
            logError(rowNum, "'Home Address' contains invalid characters (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validatePhoneNoColumn(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell);
    if (value.isEmpty()) return true;

    // Normalize the number
    value = value.trim()
                 .replaceAll("\\s+", "")
                 .replaceAll("[\\u200B-\\u200D\\uFEFF]", "");

/*     System.out.println("? Validating Phone: [" + value + "] at row " + rowNum);
    System.out.println("? Phone: [" + value + "] length=" + value.length()); */

    // Fixed regex: allows 8 digits after prefix
    if (!value.matches("^(\\+2547|2547|07|01)\\d{8}$")) {
        logError(rowNum, "'Phone No.' format is invalid (No. " + noValue + "): [" + value + "]");
        return false;
    }

        return true;
    }

    public static boolean validateBranchCodeColumn(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) {
            logError(rowNum, "'Branch Code' is blank (No. " + noValue + ")");
            return false;
        }
        if (!value.matches("^[A-Za-z0-9]+$")) {
            logError(rowNum, "'Branch Code' contains invalid characters (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateActivityCodeColumn(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) return true;
        if (!value.matches("^[A-Za-z0-9]+$")) {
            logError(rowNum, "'Activity Code' contains invalid characters (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateNationalityColumn(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) return true;
        if (!value.matches("^[A-Za-z ]+$")) {
            logError(rowNum, "'Nationality' contains invalid characters (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validatePostCodeColumn(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) return true;
        if (!value.matches("^\\d{5}$")) {
            logError(rowNum, "'Post Code' must be exactly 5 digits (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateEmailColumn(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) return true;
        if (!value.matches("^[\\w-.]+@[\\w-]+\\.[a-zA-Z]{2,}$")) {
            logError(rowNum, "'Email' format is invalid (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateResponsibilityCenterColumn(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim().replaceAll("\\s+", "").toUpperCase();

    if (value.isEmpty()) return true;

    if (!VALID_RESPONSIBILITY_CENTERS.contains(value)) {
        logError(rowNum, "'Responsibility Center' is not recognized (No. " + noValue + "): [" + value + "]");
        return false;
        }

        return true;
    }

    public static boolean validateRegistrationDate(Cell cell, int rowNum, String noValue) throws java.text.ParseException {
        if (cell == null) {
            logError(rowNum, "'Registration Date' is blank (No. " + noValue + ")");
            return false;
        }
        String dateStr = getStringValue(cell);
        if (dateStr.isEmpty()) {
            logError(rowNum, "'Registration Date' is blank (No. " + noValue + ")");
            return false;
        }
        // Parse to check format yyyy-MM-dd
        DATE_FORMAT.setLenient(false);
        DATE_FORMAT.parse(dateStr);
        return true;
    }

    public static boolean validateStatus(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) {
            logError(rowNum, "'Status' is blank (No. " + noValue + ")");
            return false;
        }
        if (!Arrays.asList(VALID_STATUS).contains(value)) {
            logError(rowNum, "'Status' value is invalid (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateSubStatus(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) return true;
        if (!Arrays.asList(VALID_SUB_STATUS).contains(value)) {
            logError(rowNum, "'Sub Status' value is invalid (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateEmployerCode(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell);
        if (value.isEmpty()) return true;
        if (!value.matches("^[A-Za-z0-9]+$")) {
            logError(rowNum, "'Employer Code' contains invalid characters (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateLocation(Cell cell, int rowNum, String noValue, String fieldName) {
        String value = getStringValue(cell);
        if (value.isEmpty()) return true;
        if (!value.matches("^[A-Za-z0-9 ]+$")) {
            logError(rowNum, "'" + fieldName + "' contains invalid characters (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateTermsOfEmployment(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell).trim();
        if (value.isEmpty()) {
            logError(rowNum, "'Terms of Employment' is blank (No. " + noValue + ")");
            return false;
        }
        boolean valid = Arrays.stream(VALID_TERMS_OF_EMPLOYMENT)
                              .anyMatch(term -> term.equalsIgnoreCase(value));
        if (!valid) {
            logError(rowNum, "'Terms of Employment' has invalid value (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateWorkStation(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell).trim();
        if (value.isEmpty()) return true;
        if (!value.matches("^[A-Za-z0-9]+$")) {
            logError(rowNum, "'Work Station' contains invalid characters (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateEmploymentDesignation(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell).trim();
        if (value.isEmpty()) {
            logError(rowNum, "'Employment Designation' is blank (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateDateOfBirth(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell).trim();
        if (value.isEmpty()) {
            logError(rowNum, "'Date of Birth' is blank (No. " + noValue + ")");
            return false;
        }
        try {
            DATE_FORMAT.setLenient(false);
            Date dob = DATE_FORMAT.parse(value);
            Date today = new Date();
            if (!dob.before(today)) {
                logError(rowNum, "'Date of Birth' must be before today (No. " + noValue + ")");
                return false;
            }
        } catch (Exception e) {
            logError(rowNum, "'Date of Birth' format is invalid (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validatePayrollStaffNo(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell).trim();
        if (value.isEmpty()) {
            logError(rowNum, "'Payroll/Staff No.' is blank (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateMaritalStatus(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell).trim();
        if (value.isEmpty()) return true;
        boolean valid = Arrays.stream(VALID_MARITAL_STATUS)
                              .anyMatch(status -> status.equalsIgnoreCase(value));
        if (!valid) {
            logError(rowNum, "'Marital Status' has invalid value (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateGender(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell).trim();
        if (value.isEmpty()) {
            logError(rowNum, "'Gender' is blank (No. " + noValue + ")");
            return false;
        }
        boolean valid = Arrays.stream(VALID_GENDERS)
                              .anyMatch(gender -> gender.equalsIgnoreCase(value));
        if (!valid) {
            logError(rowNum, "'Gender' has invalid value (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateMemberCategory(Cell cell, int rowNum, String noValue) {
        String value = getStringValue(cell).trim();
        if (value.isEmpty()) {
            logError(rowNum, "'Member Category' is blank (No. " + noValue + ")");
            return false;
        }
        boolean valid = Arrays.stream(VALID_MEMBER_CATEGORIES)
                              .anyMatch(cat -> cat.equalsIgnoreCase(value));
        if (!valid) {
            logError(rowNum, "'Member Category' has invalid value (No. " + noValue + ")");
            return false;
        }
        return true;
    }

    public static boolean validateGroupAccountNo(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) return true;
    if (!value.matches("^[A-Za-z0-9]+$")) {
        logError(rowNum, "'Group Account No.' contains invalid characters (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateTypeOfApplication(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) {
        logError(rowNum, "'Type of Application' is blank (No. " + noValue + ")");
        return false;
    }
    boolean valid = Arrays.stream(VALID_APPLICATION_TYPES)
                          .anyMatch(type -> type.equalsIgnoreCase(value));
    if (!valid) {
        logError(rowNum, "'Type of Application' has invalid value (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateMemberClass(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) {
        logError(rowNum, "'Member Class' is blank (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateAccountType(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) {
        logError(rowNum, "'Account Type' is blank (No. " + noValue + ")");
        return false;
    }
    boolean valid = Arrays.stream(VALID_ACCOUNT_TYPES)
                          .anyMatch(type -> type.equalsIgnoreCase(value));
    if (!valid) {
        logError(rowNum, "'Account Type' has invalid value (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateAccountProfile(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) {
        logError(rowNum, "'Account Profile' is blank (No. " + noValue + ")");
        return false;
    }
    boolean valid = Arrays.stream(VALID_ACCOUNT_PROFILES)
                          .anyMatch(profile -> profile.equalsIgnoreCase(value));
    if (!valid) {
        logError(rowNum, "'Account Profile' has invalid value (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateReligion(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) return true;
    if (!value.matches("^[A-Za-z]+$")) {
        logError(rowNum, "'Religion' contains non-alphabetic characters (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validatePINNumber(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) return true;
    if (!value.matches("^[A-Za-z0-9]{11}$")) {
        logError(rowNum, "'P.I.N Number' must be exactly 11 alphanumeric characters (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateRejoiningDate(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) return true;
    try {
        LocalDate.parse(value); // ISO_LOCAL_DATE format expected
        return true;
    } catch (DateTimeParseException e) {
        logError(rowNum, "'Rejoining Date' is not a valid date (No. " + noValue + ")");
        return false;
    }
}

public static boolean validatePrimaryIdentificationType(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) {
        logError(rowNum, "'Primary Identification Type' is blank (No. " + noValue + ")");
        return false;
    }
    boolean valid = Arrays.stream(VALID_ID_TYPES)
                          .anyMatch(type -> type.equalsIgnoreCase(value));
    if (!valid) {
        logError(rowNum, "'Primary Identification Type' has invalid value (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateSecondaryIdentificationType(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) return true;
    boolean valid = Arrays.stream(VALID_ID_TYPES)
                          .anyMatch(type -> type.equalsIgnoreCase(value));
    if (!valid) {
        logError(rowNum, "'Secondary Identification Type' has invalid value (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validatePrimaryIdentification(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) {
        logError(rowNum, "'Primary Identification' is blank (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateSecondaryIdentification(Cell cell, int rowNum, String noValue) {
    // Optional, no validation needed
    return true;
}

public static boolean validateFromAnotherSacco(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim().toLowerCase();
    if (!(value.equals("true") || value.equals("false"))) {
        logError(rowNum, "'From Another Sacco' must be 'true' or 'false' (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateOptionalAlphanumeric(Cell cell, int rowNum, String fieldName, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) return true;
    if (!ALPHANUMERIC_PATTERN.matcher(value).matches()) {
        logError(rowNum, "'" + fieldName + "' must be alphanumeric if present (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateOptionalAlphaOrAlphanumeric(Cell cell, int rowNum, String fieldName, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) return true;
    // allow either alphabetic or alphanumeric, so just check alphanumeric pattern here
    if (!ALPHANUMERIC_PATTERN.matcher(value).matches()) {
        logError(rowNum, "'" + fieldName + "' must be alphabetic or alphanumeric if present (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateHasJoinedBusinessLoans(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim().toLowerCase();
    if (!(value.equals("yes") || value.equals("no"))) {
        logError(rowNum, "'Has Joined Business Loans' must be 'Yes' or 'No' (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateOptionalAlphabetic(Cell cell, int rowNum, String fieldName, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) return true;
    if (!ALPHABETIC_PATTERN.matcher(value).matches()) {
        logError(rowNum, "'" + fieldName + "' must be alphabetic if present (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateContributionStartDate(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) {
        logError(rowNum, "'Contribution Start Date' is blank (No. " + noValue + ")");
        return false;
    }
    try {
        LocalDate.parse(value);
        return true;
    } catch (DateTimeParseException e) {
        logError(rowNum, "'Contribution Start Date' is not a valid date (No. " + noValue + ")");
        return false;
    }
}

public static boolean validateTransactionalMobileNumber(Cell cell, int rowNum, String noValue) {
    String value = getStringValue(cell).trim();
    if (value.isEmpty()) {
        logError(rowNum, "'Transactional Mobile Number' is blank (No. " + noValue + ")");
        return false;
    }
    if (!KENYAN_PHONE_PATTERN.matcher(value).matches()) {
        logError(rowNum, "'Transactional Mobile Number' is not a valid Kenyan phone number (No. " + noValue + ")");
        return false;
    }
    return true;
}

public static boolean validateAge(String ageStr) {
        if (ageStr == null || ageStr.trim().isEmpty()) {
            System.out.println("Error: Age is blank.");
            return false;
        }
        try {
            int age = Integer.parseInt(ageStr.trim());
            if (age < 18 || age > 120) {
                System.out.println("Error: Age must be between 18 and 120.");
                return false;
            }
        } catch (NumberFormatException e) {
            System.out.println("Error: Age must be an integer.");
            return false;
        }
        return true;
    }

    public static boolean validatePayrollNumber(String payrollNumber) {
        if (payrollNumber == null || payrollNumber.trim().isEmpty()) {
            System.out.println("Error: Payroll Number is blank.");
            return false;
        }
        return true;
    }

    public static boolean validateCompanyRegistrationNo(String companyRegNo) {
        if (companyRegNo == null || companyRegNo.trim().isEmpty()) return true;
        if (!ALPHANUMERIC_PATTERN.matcher(companyRegNo.trim()).matches()) {
            System.out.println("Error: Company Registration No. must be alphanumeric.");
            return false;
        }
        return true;
    }

    public static boolean validateAllocatedOfficer(String allocatedOfficer) {
        if (allocatedOfficer == null || allocatedOfficer.trim().isEmpty()) return true;
        if (!ALPHABETIC_PATTERN.matcher(allocatedOfficer.trim()).matches()) {
            System.out.println("Error: Allocated Officer must be alphabetic.");
            return false;
        }
        return true;
    }

    public static boolean validateContributionEffectiveDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            System.out.println("Error: Contribution Effective Date is blank.");
            return false;
        }
        try {
            LocalDate.parse(dateStr.trim());
        } catch (DateTimeParseException e) {
            System.out.println("Error: Contribution Effective Date is not a valid date.");
            return false;
        }
        return true;
    }

    public static boolean validateDelegatesZone(String delegatesZone) {
        if (delegatesZone == null || delegatesZone.trim().isEmpty()) return true;
        if (!ALPHABETIC_PATTERN.matcher(delegatesZone.trim()).matches()) {
            System.out.println("Error: Delegates Zone must be alphabetic.");
            return false;
        }
        return true;
    }

    private static void validateCountryOfOperation(Cell cell, int rowNum, String noValue) {
    if (cell == null || getStringValue(cell).isEmpty()) {
        logError(rowNum, "'Country Of Operation' is missing (No. " + noValue + ")");
    }
}

private static void validateCity(Cell cell, int rowNum, String noValue) {
    if (cell == null || getStringValue(cell).isEmpty()) {
        logError(rowNum, "'City' is missing (No. " + noValue + ")");
    }
}

private static void validateMemberNoColumn(Cell cell, int rowNum, String noValue) {
    String memberNo = getStringValue(cell);

    if (memberNo == null || memberNo.trim().isEmpty()) {
        logError(rowNum, "'No.' (Member Number) is blank");
        return;
    }

    String normalized = memberNo.trim().toUpperCase();

    if (!ALPHANUMERIC_PATTERN.matcher(normalized).matches()) {
        logError(rowNum, "'No.' (Member Number) must be alphanumeric: " + normalized);
    }

    if (!seenMemberNumbers.add(normalized)) {
        logError(rowNum, "'No.' (Member Number) is duplicated: " + normalized);
    }
}

}
