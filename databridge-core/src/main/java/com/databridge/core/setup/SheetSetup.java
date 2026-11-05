package com.databridge.core.setup;

import java.util.ArrayList;
import java.util.List;

/**
 * Template Setup for one sheet. Anything set here wins over what the template's
 * own banner row and header comments say, so a consultant can correct a client's
 * template without code changes.
 */
public class SheetSetup {

    private String sheetName;
    private String tableName;
    private Integer tableId;
    /** 1-based caption row, when automatic detection should be skipped. */
    private Integer captionRow;
    /** What the sheet feeds in Business Central, e.g. "Opening balance journal". */
    private String purpose;
    private List<FieldSetup> fields = new ArrayList<>();

    public String getSheetName() { return sheetName; }
    public String getTableName() { return tableName; }
    public Integer getTableId() { return tableId; }
    public Integer getCaptionRow() { return captionRow; }
    public String getPurpose() { return purpose; }
    public List<FieldSetup> getFields() { return fields; }

    public void setSheetName(String sheetName) { this.sheetName = sheetName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public void setTableId(Integer tableId) { this.tableId = tableId; }
    public void setCaptionRow(Integer captionRow) { this.captionRow = captionRow; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public void setFields(List<FieldSetup> fields) { this.fields = fields == null ? new ArrayList<>() : fields; }
}
