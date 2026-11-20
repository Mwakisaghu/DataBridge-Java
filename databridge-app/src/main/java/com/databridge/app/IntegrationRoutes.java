package com.databridge.app;

import com.databridge.app.http.Access;
import com.databridge.app.http.Endpoints;
import com.databridge.app.http.Response;
import com.databridge.core.integration.IntegrationSetup;
import com.databridge.core.service.IntegrationService;
import io.undertow.server.RoutingHandler;

/** Module 5: Integration Setup, import packages, import tracking and Business Central counts. */
public final class IntegrationRoutes {

    public static final String JOB_HEADER = "X-DataBridge-Package-Job";

    /** The setup and whether the secret's environment variable is set (never its value). */
    public record SetupView(IntegrationSetup setup, boolean secretPresent) { }
    public record ImportUpdate(Integer importedRows, Boolean failed, String note) { }

    private final IntegrationService integration;

    public IntegrationRoutes(IntegrationService integration) {
        this.integration = integration;
    }

    public void register(RoutingHandler r, Endpoints e) {
        r.get("/api/setup/integration", e.handler(q -> Response.ok(new SetupView(integration.setup(), integration.secretPresent()))));
        r.put("/api/setup/integration", e.handler(Access.ADMIN, q ->
                Response.ok(new SetupView(integration.putSetup(q.body(IntegrationSetup.class), q.actor()), integration.secretPresent()))));
        r.post("/api/setup/integration/test", e.handler(Access.ADMIN, q -> Response.ok(integration.testConnection(q.actor()))));

        r.post("/api/runs/{runId}/package", e.handler(Access.CONSULTANT, q -> {
            IntegrationService.Output out = integration.buildPackage(q.path("runId"), ValidationRoutes.kind(q.requiredQuery("kind")),
                    q.requiredQuery("fileName"), q.stream(), q.actor());
            return Response.file(out.workbook(), Response.XLSX, out.fileName()).withHeader(JOB_HEADER, out.job().id());
        }));
        r.get("/api/runs/{runId}/packages", e.handler(q -> Response.ok(integration.packages(q.path("runId")))));
        r.get("/api/runs/{runId}/imports", e.handler(q -> Response.ok(integration.imports(q.path("runId")))));
        r.put("/api/runs/{runId}/imports/{sheet}", e.handler(Access.CONSULTANT, q -> {
            ImportUpdate b = q.body(ImportUpdate.class);
            return Response.ok(integration.recordImport(q.path("runId"), q.path("sheet"), b.importedRows(),
                    Boolean.TRUE.equals(b.failed()), b.note(), q.actor()));
        }));
        r.post("/api/runs/{runId}/imports/verify", e.handler(Access.CONSULTANT, q -> Response.ok(integration.verify(q.path("runId"), q.actor()))));
    }
}
