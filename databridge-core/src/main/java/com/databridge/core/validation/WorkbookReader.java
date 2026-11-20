package com.databridge.core.validation;

import com.databridge.core.domain.TemplateKind;
import com.databridge.core.schema.FieldDef;
import com.databridge.core.schema.FieldType;
import com.databridge.core.schema.TableSchema;
import com.databridge.core.schema.WorkbookSchema;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the data rows of every sheet, below the caption row that the schema found. Rows with no
 * value in any known column are skipped. Formula cells give their cached result.
 */
public final class WorkbookReader {

    private WorkbookReader() { }

    public static List<SheetData> read(Path file, WorkbookSchema schema, TemplateKind kind) throws IOException {
        try (Workbook wb = WorkbookFactory.create(file.toFile(), null, true)) {
            List<SheetData> out = new ArrayList<>();
            for (TableSchema t : schema.sheets()) {
                Sheet sheet = wb.getSheet(t.sheetName());
                if (sheet == null || t.fields().isEmpty()) {
                    out.add(new SheetData(kind, t, List.of()));
                    continue;
                }
                List<DataRow> rows = new ArrayList<>();
                for (int r = t.dataStartRow() - 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (FieldDef f : t.fields()) {
                        Object v = value(row.getCell(f.columnIndex()), f.type());
                        if (v != null) values.put(f.caption(), v);
                    }
                    if (!values.isEmpty()) rows.add(new DataRow(r + 1, values));
                }
                out.add(new SheetData(kind, t, rows));
            }
            return out;
        }
    }

    public static Object value(Cell cell, FieldType type) {
        if (cell == null) return null;
        CellType ct = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        switch (ct) {
            case STRING: {
                String s = cell.getStringCellValue();
                return s == null || s.isBlank() ? null : s;
            }
            case NUMERIC: {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime t = cell.getLocalDateTimeCellValue();
                    if (type == FieldType.TIME) return t.toLocalTime();
                    if (type == FieldType.DATETIME) return t;
                    return t.toLocalDate();
                }
                return BigDecimal.valueOf(cell.getNumericCellValue());
            }
            case BOOLEAN:
                return cell.getBooleanCellValue();
            case ERROR:
                return "#ERROR";
            default:
                return null;
        }
    }
}
