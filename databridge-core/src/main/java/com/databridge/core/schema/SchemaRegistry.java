package com.databridge.core.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The schema registry for a run: one {@link WorkbookSchema} per template workbook.
 * It holds template structure only, so it is safe to keep after client files are purged.
 */
public final class SchemaRegistry {

    private final List<WorkbookSchema> workbooks = new ArrayList<>();

    public void add(WorkbookSchema schema) { workbooks.add(schema); }

    public List<WorkbookSchema> workbooks() { return Collections.unmodifiableList(workbooks); }

    public int sheetCount() {
        return workbooks.stream().mapToInt(w -> w.sheets().size()).sum();
    }

    public void writeJson(Path file) throws IOException {
        ObjectMapper m = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        m.writeValue(file.toFile(), workbooks);
    }
}
