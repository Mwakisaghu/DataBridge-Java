package com.databridge.core.validation;

import com.databridge.core.domain.TemplateKind;
import com.databridge.core.schema.FieldDef;
import com.databridge.core.schema.TableSchema;
import com.databridge.core.setup.TemplateSetup;

import java.util.List;
import java.util.Optional;

/** A sheet's schema and its data rows, from one workbook of a submission. */
public record SheetData(TemplateKind kind, TableSchema schema, List<DataRow> rows) {

    public SheetData {
        rows = List.copyOf(rows);
    }

    public String name() {
        return schema.sheetName();
    }

    /** The column with this caption, matched ignoring case and spacing. */
    public Optional<FieldDef> field(String caption) {
        String want = TemplateSetup.normalise(caption);
        return schema.fields().stream().filter(f -> TemplateSetup.normalise(f.caption()).equals(want)).findFirst();
    }
}
