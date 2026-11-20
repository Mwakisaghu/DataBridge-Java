package com.databridge.core.integration;

import java.util.ArrayList;
import java.util.List;

/**
 * The Integration Setup: how to reach the client's Business Central and how packages are gated.
 * The client secret is never stored: {@code clientSecretEnv} names the environment variable of the
 * DataBridge server that holds it.
 */
public class IntegrationSetup {
    private String tenantId;
    private String environment = "Production";
    private String companyId;
    private String clientId;
    private String clientSecretEnv = "DATABRIDGE_BC_CLIENT_SECRET";
    private String tokenUrl = "https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token";
    private String apiBaseUrl = "https://api.businesscentral.dynamics.com/v2.0/{tenantId}/{environment}";
    private String scope = "https://api.businesscentral.dynamics.com/.default";
    private int timeoutSeconds = 30;
    /** Refuse to build a package unless the file was validated in the run's latest check with no open Critical finding. */
    private boolean requireCleanCheck = true;
    private List<EntityMap> entities = new ArrayList<>();

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getCompanyId() { return companyId; }
    public void setCompanyId(String companyId) { this.companyId = companyId; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecretEnv() { return clientSecretEnv; }
    public void setClientSecretEnv(String clientSecretEnv) { this.clientSecretEnv = clientSecretEnv; }
    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public boolean isRequireCleanCheck() { return requireCleanCheck; }
    public void setRequireCleanCheck(boolean requireCleanCheck) { this.requireCleanCheck = requireCleanCheck; }
    public List<EntityMap> getEntities() { return entities; }
    public void setEntities(List<EntityMap> entities) { this.entities = entities == null ? new ArrayList<>() : entities; }

    /** True when enough is set to call Business Central. */
    public boolean connectable() {
        return notBlank(tenantId) && notBlank(clientId) && notBlank(companyId) && notBlank(environment);
    }

    String resolve(String template) {
        return template.replace("{tenantId}", tenantId == null ? "" : tenantId)
                .replace("{environment}", environment == null ? "" : environment);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
