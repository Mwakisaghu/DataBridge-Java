package com.databridge.core.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the type notes that the Sky World templates carry as header-cell comments,
 * for example {@code Code20}, {@code Text100}, {@code Decimal}, or
 * <pre>
 * Option
 * 0: Posting
 * 1: Heading
 * </pre>
 * Also accepts the {@code Options:} / {@code 1.Fixed} variant some sheets use, and
 * skips leading lines that are not a type (such as an author name Excel adds).
 */
public final class FieldTypeParser {

    private static final Pattern SIZED = Pattern.compile("^(code|text)\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTION_LINE = Pattern.compile("^\\s*(\\d+)\\s*[:.]\\s*(.*)$");

    private FieldTypeParser() { }

    public static FieldSpec parse(String note) {
        if (note == null || note.isBlank()) return FieldSpec.unknown();
        String[] lines = note.replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            Matcher sized = SIZED.matcher(line);
            if (sized.matches()) {
                FieldType t = sized.group(1).equalsIgnoreCase("code") ? FieldType.CODE : FieldType.TEXT;
                return new FieldSpec(t, Integer.parseInt(sized.group(2)), List.of());
            }

            String key = line.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            switch (key) {
                case "integer": return FieldSpec.of(FieldType.INTEGER);
                case "biginteger": return FieldSpec.of(FieldType.BIGINTEGER);
                case "decimal": return FieldSpec.of(FieldType.DECIMAL);
                case "boolean": return FieldSpec.of(FieldType.BOOLEAN);
                case "date": return FieldSpec.of(FieldType.DATE);
                case "time": return FieldSpec.of(FieldType.TIME);
                case "datetime": return FieldSpec.of(FieldType.DATETIME);
                case "dateformula": return FieldSpec.of(FieldType.DATEFORMULA);
                case "guid": return FieldSpec.of(FieldType.GUID);
                default: break;
            }
            if (key.equals("option") || key.equals("options")) {
                List<OptionValue> options = new ArrayList<>();
                for (int j = i + 1; j < lines.length; j++) {
                    Matcher m = OPTION_LINE.matcher(lines[j]);
                    if (m.matches()) {
                        options.add(new OptionValue(Integer.parseInt(m.group(1)), m.group(2).trim()));
                    }
                }
                return new FieldSpec(FieldType.OPTION, null, options);
            }
        }
        return FieldSpec.unknown();
    }
}
