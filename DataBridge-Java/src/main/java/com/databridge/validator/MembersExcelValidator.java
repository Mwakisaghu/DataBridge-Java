package com.databridge.validator;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Iterator;

public class MembersExcelValidator {

    public static void main(String[] args) {
        try {
            // Open the Excel file
            FileInputStream file = new FileInputStream(new File("Members.xlsx"));
            Workbook workbook = new XSSFWorkbook(file);
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            // Skip header row
            rowIterator.next();

            // Iterate through each row and validate the columns
            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();

                // Validate "No." column (first column, index 0)
                String noValue = row.getCell(0) != null ? row.getCell(0).getStringCellValue() : "";
                
                // Validate "Salutation" column (second column, index 1)
                if (!validateSalutationColumn(row.getCell(1), row.getRowNum(), noValue)) {
                }
            }

            workbook.close();
            file.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Validation method for the "No." column (checking for blanks)
    public static boolean validateNoColumn(Cell cell, int rowNum) {
        if (cell == null || cell.getCellType() != CellType.STRING) {
            System.out.println("Blank or invalid 'No.' at row " + rowNum + " with cell value: " + (cell == null ? "null" : cell.getStringCellValue()));
            return false;
        }
        String value = cell.getStringCellValue();
        if (value.trim().isEmpty()) {
            System.out.println("Blank 'No.' at row " + rowNum + " with cell value: " + value);
            return false;
        }
        return true;
    }

    // Validation method for the "Salutation" column (checking for blanks)
    public static boolean validateSalutationColumn(Cell cell, int rowNum, String noValue) {
        if (cell == null || cell.getCellType() != CellType.STRING) {
            System.out.println("Blank or invalid 'Salutation' at row " + rowNum + " with No. value: " + noValue);
            return false;
        }
        String value = cell.getStringCellValue();
        if (value.trim().isEmpty()) {
            System.out.println("Blank 'Salutation' at row " + rowNum + " with No. value: " + noValue);
            return false;
        }
        return true;
    }
}

