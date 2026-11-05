package com.databridge.core.support;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds small synthetic template workbooks. Tests never use real client files. */
public final class TestWorkbooks {

    private TestWorkbooks() { }

    public static XSSFWorkbook workbook() {
        return new XSSFWorkbook();
    }

    public static Cell set(Sheet sheet, int row, int col, Object value) {
        Row r = sheet.getRow(row) == null ? sheet.createRow(row) : sheet.getRow(row);
        Cell c = r.getCell(col) == null ? r.createCell(col) : r.getCell(col);
        if (value instanceof Number n) c.setCellValue(n.doubleValue());
        else if (value instanceof Boolean b) c.setCellValue(b);
        else c.setCellValue(String.valueOf(value));
        return c;
    }

    /** Writes a caption with a header comment, the way the templates carry BC types. */
    public static void caption(Sheet sheet, int row, int col, String caption, String typeNote) {
        Cell c = set(sheet, row, col, caption);
        if (typeNote == null) return;
        CreationHelper helper = sheet.getWorkbook().getCreationHelper();
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(col);
        anchor.setCol2(col + 2);
        anchor.setRow1(row);
        anchor.setRow2(row + 4);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(helper.createRichTextString(typeNote));
        c.setCellComment(comment);
    }

    public static Path save(XSSFWorkbook wb, Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            wb.write(out);
        }
        wb.close();
        return file;
    }
}
