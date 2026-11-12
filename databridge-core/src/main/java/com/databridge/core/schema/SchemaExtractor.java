package com.databridge.core.schema;

import com.databridge.core.setup.FieldSetup;
import com.databridge.core.setup.SheetSetup;
import com.databridge.core.setup.TemplateSetup;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the schema of every sheet in a template workbook from three sources, in
 * rising priority: the template's caption row, its header comments, and the
 * Template Setup. Reads captions and comments only; never reads data rows.
 */
public final class SchemaExtractor {

    private static final int MAX_LISTED = 5;

    private final TemplateSetup setup;
    private final int captionScanRows;

    public SchemaExtractor(TemplateSetup setup, int captionScanRows) {
        this.setup = setup;
        this.captionScanRows = captionScanRows;
    }

    public WorkbookSchema extract(Path workbookFile) throws IOException {
        try (Workbook wb = WorkbookFactory.create(workbookFile.toFile(), null, true)) {
            return extract(wb, workbookFile.getFileName().toString());
        }
    }

    public WorkbookSchema extract(Workbook wb, String name) {
        List<TableSchema> sheets = new ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            sheets.add(extractSheet(wb.getSheetAt(i), wb.isSheetHidden(i) || wb.isSheetVeryHidden(i)));
        }
        return new WorkbookSchema(name, sheets);
    }

    TableSchema extractSheet(Sheet sheet, boolean hidden) {
        List<String> warnings = new ArrayList<>();
        if (hidden) warnings.add("Sheet is hidden in the workbook");
        Optional<SheetSetup> sheetSetup = setup.find(sheet.getSheetName());

        SheetLayout.Banner banner = SheetLayout.readBanner(sheet);
        int captionIdx;
        if (sheetSetup.isPresent() && sheetSetup.get().getCaptionRow() != null) {
            captionIdx = sheetSetup.get().getCaptionRow() - 1;
        } else {
            captionIdx = SheetLayout.findCaptionRow(sheet, captionScanRows, banner != null);
        }

        String packageCode = banner == null ? null : banner.packageCode();
        String tableName = banner == null ? null : banner.tableName();
        Integer tableId = banner == null ? null : banner.tableId();
        String purpose = null;
        if (sheetSetup.isPresent()) {
            SheetSetup s = sheetSetup.get();
            if (s.getTableName() != null) tableName = s.getTableName();
            if (s.getTableId() != null) tableId = s.getTableId();
            purpose = s.getPurpose();
        }

        if (captionIdx < 0) {
            warnings.add("No caption row found in the first " + captionScanRows + " rows");
            return new TableSchema(sheet.getSheetName(), packageCode, tableName, tableId, purpose,
                    0, 0, List.of(), warnings);
        }

        Map<String, FieldSetup> fieldSetups = new LinkedHashMap<>();
        sheetSetup.ifPresent(s -> s.getFields().forEach(f -> fieldSetups.put(TemplateSetup.normalise(f.getCaption()), f)));

        List<FieldDef> fields = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();
        List<String> untyped = new ArrayList<>();
        Row row = sheet.getRow(captionIdx);
        if (row != null) {
            for (Cell cell : row) {
                String caption = SheetLayout.text(cell);
                if (caption.isEmpty()) continue;
                String col = CellReference.convertNumToColString(cell.getColumnIndex());
                String key = TemplateSetup.normalise(caption);

                if (seen.containsKey(key)) {
                    warnings.add("Duplicate caption '" + caption + "' in columns " + seen.get(key) + " and " + col);
                } else {
                    seen.put(key, col);
                }

                FieldSpec spec = FieldSpec.unknown();
                FieldSource source = FieldSource.NONE;
                Comment comment = cell.getCellComment();
                if (comment != null && comment.getString() != null) {
                    spec = FieldTypeParser.parse(comment.getString().getString());
                    if (spec.isKnown()) source = FieldSource.TEMPLATE_COMMENT;
                }
                boolean required = false;
                FieldSetup fs = fieldSetups.remove(key);
                if (fs != null) {
                    required = fs.isRequired();
                    FieldSpec fromSetup = specFromSetup(fs);
                    if (fromSetup.isKnown()) {
                        spec = fromSetup;
                        source = FieldSource.TEMPLATE_SETUP;
                    }
                }
                if (!spec.isKnown()) untyped.add(caption);
                fields.add(new FieldDef(cell.getColumnIndex(), col, caption, spec.type(), spec.length(),
                        spec.options(), required, source));
            }
        }

        if (fields.isEmpty()) warnings.add("Caption row " + (captionIdx + 1) + " is empty");
        if (!untyped.isEmpty()) {
            warnings.add(untyped.size() + " column(s) have no type in the template or Template Setup: "
                    + list(untyped));
        }
        if (!fieldSetups.isEmpty()) {
            List<String> missing = fieldSetups.values().stream().map(FieldSetup::getCaption).toList();
            warnings.add("Template Setup expects column(s) not found in the sheet: " + list(missing));
        }
        if (tableId == null && purpose == null) {
            warnings.add("No BC table ID in the banner row and no Template Setup for this sheet");
        }

        return new TableSchema(sheet.getSheetName(), packageCode, tableName, tableId, purpose,
                captionIdx + 1, captionIdx + 2, fields, warnings);
    }

    private static FieldSpec specFromSetup(FieldSetup fs) {
        FieldSpec spec = FieldTypeParser.parse(fs.getType());
        if (spec.type() == FieldType.OPTION && spec.options().isEmpty() && !fs.getOptions().isEmpty()) {
            List<OptionValue> opts = new ArrayList<>();
            for (int i = 0; i < fs.getOptions().size(); i++) opts.add(new OptionValue(i, fs.getOptions().get(i)));
            spec = new FieldSpec(FieldType.OPTION, null, opts);
        }
        return spec;
    }

    private static String list(List<String> items) {
        String shown = String.join(", ", items.subList(0, Math.min(MAX_LISTED, items.size())));
        return items.size() > MAX_LISTED ? shown + " and " + (items.size() - MAX_LISTED) + " more" : shown;
    }
}
