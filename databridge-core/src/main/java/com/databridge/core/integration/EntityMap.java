package com.databridge.core.integration;

/**
 * Where Business Central exposes the records of one template sheet, so DataBridge can count them
 * after import: {@code apiPath} (e.g. {@code api/v2.0}, or a custom API such as
 * {@code api/skyworld/sacco/v1.0}) and the entity set (e.g. {@code customers}).
 */
public class EntityMap {
    private String sheet;
    private String apiPath = "api/v2.0";
    private String entitySet;

    public EntityMap() { }

    public EntityMap(String sheet, String apiPath, String entitySet) {
        this.sheet = sheet;
        this.apiPath = apiPath;
        this.entitySet = entitySet;
    }

    public String getSheet() { return sheet; }
    public void setSheet(String sheet) { this.sheet = sheet; }
    public String getApiPath() { return apiPath; }
    public void setApiPath(String apiPath) { this.apiPath = apiPath; }
    public String getEntitySet() { return entitySet; }
    public void setEntitySet(String entitySet) { this.entitySet = entitySet; }
}
