package com.databridge.core.setup;

/**
 * DataBridge Setup: firm-wide settings, the equivalent of a Business Central
 * setup page. Defaults ship in {@code /setup/databridge-setup.json} and can be
 * overridden per installation by a {@code databridge-setup.json} in the setup folder.
 */
public class DataBridgeSetup {

    /** Root folder for workspaces. Supports ${java.io.tmpdir} and ${user.home}. */
    private String workspaceDir = "${java.io.tmpdir}/databridge-workspaces";
    private RetentionSetup retention = new RetentionSetup();
    private FindingValueMode findingValueMode = FindingValueMode.MASKED;
    /** How many rows from the top of a sheet to search for the caption row. */
    private int captionScanRows = 10;
    /** Largest workbook accepted, in megabytes. */
    private int maxUploadMb = 200;

    public String getWorkspaceDir() { return workspaceDir; }
    public RetentionSetup getRetention() { return retention; }
    public FindingValueMode getFindingValueMode() { return findingValueMode; }
    public int getCaptionScanRows() { return captionScanRows; }
    public int getMaxUploadMb() { return maxUploadMb; }

    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
    public void setRetention(RetentionSetup retention) { this.retention = retention; }
    public void setFindingValueMode(FindingValueMode findingValueMode) { this.findingValueMode = findingValueMode; }
    public void setCaptionScanRows(int captionScanRows) { this.captionScanRows = captionScanRows; }
    public void setMaxUploadMb(int maxUploadMb) { this.maxUploadMb = maxUploadMb; }

    /** Workspace folder with ${...} placeholders resolved. */
    public String resolvedWorkspaceDir() {
        return workspaceDir
                .replace("${java.io.tmpdir}", System.getProperty("java.io.tmpdir"))
                .replace("${user.home}", System.getProperty("user.home"));
    }

    void validate() {
        if (workspaceDir == null || workspaceDir.isBlank()) throw new SetupException("workspaceDir is required");
        if (retention == null) throw new SetupException("retention is required");
        retention.validate();
        if (findingValueMode == null) throw new SetupException("findingValueMode is required");
        if (captionScanRows < 1 || captionScanRows > 50) throw new SetupException("captionScanRows must be 1-50");
        if (maxUploadMb < 1 || maxUploadMb > 2048) throw new SetupException("maxUploadMb must be 1-2048");
    }
}
