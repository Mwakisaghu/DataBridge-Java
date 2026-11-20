package com.databridge.app;

import com.databridge.app.http.Access;
import com.databridge.app.http.Endpoints;
import com.databridge.app.http.Response;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.service.ReportingService;
import com.databridge.core.service.ValidationException;
import io.undertow.server.RoutingHandler;

/** Module 6: run summaries and KPIs, sign-off with certificate, sign-off pack and audit export. */
public final class ReportingRoutes {

    public record SignOffRequest(String note) { }
    public record SignOffDecision(Boolean approve, String note) { }

    private final ReportingService reporting;

    public ReportingRoutes(ReportingService reporting) {
        this.reporting = reporting;
    }

    public void register(RoutingHandler r, Endpoints e) {
        r.get("/api/runs/{runId}/summary", e.handler(q -> Response.ok(reporting.summary(q.path("runId")))));
        r.post("/api/runs/{runId}/signoff", e.handler(Access.CONSULTANT, q -> {
            SignOffRequest b = q.optionalBody(SignOffRequest.class);
            return Response.ok(reporting.requestSignOff(q.path("runId"), b == null ? null : b.note(), q.actor()));
        }));
        r.post("/api/runs/{runId}/signoff/decision", e.handler(Access.FINANCE, q -> {
            SignOffDecision d = q.body(SignOffDecision.class);
            if (d.approve() == null) throw new ValidationException("Say whether the sign-off is approved (approve: true or false)");
            return Response.ok(reporting.decideSignOff(q.path("runId"), d.approve(), d.note(), q.actor()));
        }));
        r.get("/api/runs/{runId}/signoff-pack.xlsx", e.handler(q -> {
            MigrationRun run = reporting.summary(q.path("runId")).run();
            return Response.file(reporting.signOffPack(run.id()), Response.XLSX, "signoff-pack-run-" + run.runNo() + ".xlsx");
        }));
        r.get("/api/audit.xlsx", e.handler(q -> {
            String limit = q.query("limit");
            int n;
            try {
                n = limit == null || limit.isBlank() ? 1000 : Integer.parseInt(limit.trim());
            } catch (NumberFormatException ex) {
                throw new ValidationException("'" + limit + "' is not a whole number");
            }
            return Response.file(reporting.auditExport(blank(q.query("entityType")), blank(q.query("entityId")), n), Response.XLSX,
                    "audit-trail.xlsx");
        }));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
