package com.databridge.core.cleaning;

/**
 * One line of a cleaning change log: how many cells a rule changed in one column. Holds where and
 * what kind of change, never a value.
 *
 * @param rows the first changed row numbers, comma-separated, for finding them in the file
 */
public record CleanChange(String sheet, String field, String column, String ruleId, CleanAction action, int cells, String rows) { }
