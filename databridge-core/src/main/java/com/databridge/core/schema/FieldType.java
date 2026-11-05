package com.databridge.core.schema;

/** Business Central field types as written in the template header comments. */
public enum FieldType {
    CODE, TEXT, INTEGER, BIGINTEGER, DECIMAL, BOOLEAN, DATE, TIME, DATETIME, DATEFORMULA, GUID, OPTION,
    /** No type metadata was found in the comment or the Template Setup. */
    UNKNOWN
}
