package com.databridge.core.cleaning;

import java.util.List;

/** What cleaning changed in a workbook. */
public record CleanResult(List<CleanChange> changes, List<String> skipped) {

    public CleanResult {
        changes = List.copyOf(changes);
        skipped = List.copyOf(skipped);
    }

    public int cells() {
        return changes.stream().filter(c -> c.action() != CleanAction.REMOVE_DUPLICATE_ROWS).mapToInt(CleanChange::cells).sum();
    }

    public int rowsEmptied() {
        return changes.stream().filter(c -> c.action() == CleanAction.REMOVE_DUPLICATE_ROWS).mapToInt(CleanChange::cells).sum();
    }
}
