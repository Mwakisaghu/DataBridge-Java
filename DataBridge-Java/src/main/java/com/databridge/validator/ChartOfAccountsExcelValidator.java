package com.databridge.validator;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

public class ChartOfAccountsExcelValidator {

    private static final Logger logger = LoggerFactory.getLogger(ChartOfAccountsExcelValidator.class);
    private static final Map<Integer, List<String>> rowErrors = new HashMap<>();
    private static final Set<String> seenAccountNumbers = new HashSet<>();
    private static final Pattern ACCOUNT_NO_PATTERN = Pattern.compile("^\\d{5,6}$");

    public Map<Integer, List<String>> validate(InputStream inputStream) {
        rowErrors.clear();
        seenAccountNumbers.clear();

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            // skip header
            if (rowIterator.hasNext()) rowIterator.next();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                int rowNum = row.getRowNum();
                String glNo = getCell(row, 0);
                String name = getCell(row, 2);
                String accountType = getCell(row, 3);
                String accountCategory = getCell(row, 4);
                String incomeBalance = getCell(row, 5);
                String debitCredit = getCell(row, 6);
                String blocked = getCell(row, 7);
                String directPosting = getCell(row, 8);
                String genPostingType = getCell(row, 9);
                String genBusGroup = getCell(row, 10);
                String genProdGroup = getCell(row, 11);

                boolean isBlocked = "true".equalsIgnoreCase(blocked);
                boolean isDirectPosting = "true".equalsIgnoreCase(directPosting);
                boolean isUsedInPostingGroups = !genPostingType.isEmpty() || !genBusGroup.isEmpty() || !genProdGroup.isEmpty();

                // ========== VALIDATIONS ==========
                validateNotBlank(glNo, rowNum, "No.");
                validateAccountNoFormat(glNo, rowNum);
                validateDuplicateAccountNo(glNo, rowNum);
                validateNotBlank(name, rowNum, "Name");

                validatePostingLogic(accountType, isDirectPosting, rowNum, glNo);
                validateCategoryBalanceAlignment(accountCategory, incomeBalance, rowNum, glNo);
                validateBlockedAndPostingGroups(isBlocked, isUsedInPostingGroups, rowNum, glNo);
                validateAccountNumberPrefix(glNo, accountCategory, rowNum, glNo);
                validateDebitCreditAlignment(accountCategory, debitCredit, rowNum, glNo);
            }

            reportResults();

        } catch (Exception e) {
            logger.error("Error processing Chart of Accounts Excel file", e);
        }

        return rowErrors;
    }

    private void validateNotBlank(String value, int rowNum, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            logError(rowNum, fieldName + " is required.");
        }
    }

    private void validateAccountNoFormat(String accountNo, int rowNum) {
        if (!ACCOUNT_NO_PATTERN.matcher(accountNo).matches()) {
            logError(rowNum, "Account No. must be numeric and 5–6 digits.");
        }
    }

    private void validateDuplicateAccountNo(String accountNo, int rowNum) {
        if (!seenAccountNumbers.add(accountNo)) {
            logError(rowNum, "Duplicate G/L Account No.");
        }
    }

    private void validatePostingLogic(String type, boolean directPosting, int rowNum, String no) {
        if ("Begin-Total".equalsIgnoreCase(type) || "End-Total".equalsIgnoreCase(type)) {
            if (directPosting) {
                logError(rowNum, type + " account should not allow Direct Posting (No. " + no + ")");
            }
        }
    }

    private void validateCategoryBalanceAlignment(String category, String balance, int rowNum, String no) {
        if (category == null || balance == null) return;

        if (("Income".equalsIgnoreCase(category) || "Expense".equalsIgnoreCase(category)) &&
                !"Income Statement".equalsIgnoreCase(balance)) {
            logError(rowNum, category + " accounts must be 'Income Statement' (No. " + no + ")");
        }

        if (("Assets".equalsIgnoreCase(category) || "Liabilities".equalsIgnoreCase(category) || "Equity".equalsIgnoreCase(category)) &&
                !"Balance Sheet".equalsIgnoreCase(balance)) {
            logError(rowNum, category + " accounts must be 'Balance Sheet' (No. " + no + ")");
        }
    }

    private void validateBlockedAndPostingGroups(boolean blocked, boolean usedInPostingGroup, int rowNum, String no) {
        if (blocked && usedInPostingGroup) {
            logError(rowNum, "Blocked account should not appear in posting groups (No. " + no + ")");
        }
    }

    private void validateAccountNumberPrefix(String accountNo, String category, int rowNum, String no) {
        if (accountNo != null && category != null && accountNo.length() > 0) {
            char firstDigit = accountNo.charAt(0);
            switch (category.toLowerCase()) {
                case "assets":
                    if (firstDigit != '1') logError(rowNum, "Assets should begin with 1xxx (No. " + no + ")");
                    break;
                case "liabilities":
                    if (firstDigit != '2') logError(rowNum, "Liabilities should begin with 2xxx (No. " + no + ")");
                    break;
                case "equity":
                    if (firstDigit != '3') logError(rowNum, "Equity should begin with 3xxx (No. " + no + ")");
                    break;
                case "income":
                    if (firstDigit != '4') logError(rowNum, "Income should begin with 4xxx (No. " + no + ")");
                    break;
                case "expense":
                    if (firstDigit != '5') logError(rowNum, "Expense should begin with 5xxx (No. " + no + ")");
                    break;
            }
        }
    }

    private void validateDebitCreditAlignment(String category, String debitCredit, int rowNum, String no) {
        if (category == null || debitCredit == null) return;

        switch (category.toLowerCase()) {
            case "assets":
            case "expense":
                if (!debitCredit.equalsIgnoreCase("debit") && !debitCredit.equalsIgnoreCase("both")) {
                    logError(rowNum, category + " accounts should typically have 'Debit' or 'Both' as balance (No. " + no + ")");
                }
                break;
            case "liabilities":
            case "equity":
            case "income":
                if (!debitCredit.equalsIgnoreCase("credit") && !debitCredit.equalsIgnoreCase("both")) {
                    logError(rowNum, category + " accounts should typically have 'Credit' or 'Both' as balance (No. " + no + ")");
                }
                break;
        }
    }

    private static String getCell(Row row, int index) {
        Cell cell = row.getCell(index);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((int) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue().trim();
                } catch (IllegalStateException e) {
                    yield String.valueOf((int) cell.getNumericCellValue());
                }
            }
            default -> "";
        };
    }

    private void logError(int rowNum, String message) {
        rowErrors.computeIfAbsent(rowNum, k -> new ArrayList<>()).add(message);
    }

    private void reportResults() {
        if (rowErrors.isEmpty()) {
            logger.info("Chart of Accounts validation completed successfully. No errors found.");
        } else {
            logger.warn("Validation completed with issues:");
            rowErrors.forEach((row, issues) -> issues.forEach(issue -> logger.warn("Row {}: {}", row, issue)));
        }
    }
}
