package com.databridge.core.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FieldTypeParserTest {

    @Test
    void codeAndTextCarryLength() {
        FieldSpec code = FieldTypeParser.parse("Code20");
        assertEquals(FieldType.CODE, code.type());
        assertEquals(20, code.length());
        FieldSpec text = FieldTypeParser.parse("Text100");
        assertEquals(FieldType.TEXT, text.type());
        assertEquals(100, text.length());
    }

    @Test
    void simpleTypes() {
        assertEquals(FieldType.DECIMAL, FieldTypeParser.parse("Decimal").type());
        assertEquals(FieldType.BOOLEAN, FieldTypeParser.parse("Boolean").type());
        assertEquals(FieldType.DATE, FieldTypeParser.parse("Date").type());
        assertEquals(FieldType.DATEFORMULA, FieldTypeParser.parse("DateFormula").type());
        assertEquals(FieldType.INTEGER, FieldTypeParser.parse("Integer").type());
        assertNull(FieldTypeParser.parse("Decimal").length());
    }

    @Test
    void optionWithBlankFirstValue() {
        FieldSpec s = FieldTypeParser.parse("Option\n0:  \n1: Male\n2: Female");
        assertEquals(FieldType.OPTION, s.type());
        assertEquals(3, s.options().size());
        assertEquals(new OptionValue(0, ""), s.options().get(0));
        assertEquals(new OptionValue(2, "Female"), s.options().get(2));
    }

    @Test
    void dottedOptionsVariant() {
        FieldSpec s = FieldTypeParser.parse("Options:\n0.\n1.Fixed\n2.Varied\n");
        assertEquals(FieldType.OPTION, s.type());
        assertEquals(new OptionValue(1, "Fixed"), s.options().get(1));
    }

    @Test
    void optionsKeepTheirOrdinalsWhenListStartsLate() {
        FieldSpec s = FieldTypeParser.parse("Options:\n1.External Debt Collectors\n");
        assertEquals(new OptionValue(1, "External Debt Collectors"), s.options().get(0));
    }

    @Test
    void skipsAuthorLineExcelAdds() {
        FieldSpec s = FieldTypeParser.parse("Harrison Mwakisaghu:\nCode30");
        assertEquals(FieldType.CODE, s.type());
        assertEquals(30, s.length());
    }

    @Test
    void unknownOrBlankNote() {
        assertEquals(FieldType.UNKNOWN, FieldTypeParser.parse("staff number in charge").type());
        assertEquals(FieldType.UNKNOWN, FieldTypeParser.parse("  ").type());
        assertEquals(FieldType.UNKNOWN, FieldTypeParser.parse(null).type());
    }
}
