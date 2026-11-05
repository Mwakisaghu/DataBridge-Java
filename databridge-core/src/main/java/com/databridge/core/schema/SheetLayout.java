package com.databridge.core.schema;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellAddress;

import java.util.Map;

/**
 * Finds the parts of a template sheet that sit above the data:
 * <ul>
 *   <li>the Config Package banner in row 1 (package code, table name, numeric table ID),
 *       e.g. {@code STATIC DATA | Members | 52185700};</li>
 *   <li>the caption row, which is the row carrying the most header comments, or,
 *       for sheets without comments, the first row that looks like captions.</li>
 * </ul>
 * Notes rows between the banner and the captions are skipped automatically.
 */
public final class SheetLayout {

    private static final DataFormatter FORMAT = new DataFormatter();

    /** Banner values from row 1, or null when row 1 is not a banner. */
    public record Banner(String packageCode, String tableName, Integer tableId) { }

    private SheetLayout() { }

    public static Banner readBanner(Sheet sheet) {
        Row row = sheet.getRow(0);
        if (row == null) return null;
        String a = text(row.getCell(0));
        String b = text(row.getCell(1));
        Integer id = integer(row.getCell(2));
        if (a.isEmpty() || b.isEmpty() || id == null) return null;
        for (int c = 3; c < Math.max(row.getLastCellNum(), 3); c++) {
            if (!text(row.getCell(c)).isEmpty()) return null;
        }
        return new Banner(a, b, id);
    }

    /** 0-based caption row index, or -1 when none can be found. */
    public static int findCaptionRow(Sheet sheet, int scanRows, boolean hasBanner) {
        int limit = Math.min(scanRows, sheet.getLastRowNum() + 1);
        int[] commentsPerRow = new int[Math.max(limit, 1)];
        Map<CellAddress, ? extends Comment> comments = sheet.getCellComments();
        for (CellAddress addr : comments.keySet()) {
            if (addr.getRow() < limit) commentsPerRow[addr.getRow()]++;
        }
        int best = -1;
        for (int r = 0; r < limit; r++) {
            if (commentsPerRow[r] > 0 && (best < 0 || commentsPerRow[r] > commentsPerRow[best])) best = r;
        }
        if (best >= 0) return best;

        for (int r = hasBanner ? 1 : 0; r < limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int strings = 0, nonBlank = 0;
            for (Cell cell : row) {
                if (text(cell).isEmpty()) continue;
                nonBlank++;
                if (cell.getCellType() == CellType.STRING) strings++;
            }
            if (strings >= 2 && strings * 10 >= nonBlank * 8) return r;
        }
        return -1;
    }

    static String text(Cell cell) {
        if (cell == null) return "";
        return FORMAT.formatCellValue(cell).trim().replaceAll("\\s+", " ");
    }

    private static Integer integer(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                double d = cell.getNumericCellValue();
                return d == Math.rint(d) && d > 0 && d < Integer.MAX_VALUE ? (int) d : null;
            }
            String s = text(cell);
            return s.matches("\\d{1,9}") ? Integer.valueOf(s) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
