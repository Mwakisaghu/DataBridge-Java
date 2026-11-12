package com.databridge.app;

import com.databridge.app.http.Endpoints;
import com.databridge.app.http.Request;
import com.databridge.app.http.Response;
import com.databridge.core.domain.DatasetVersion;
import com.databridge.core.domain.RunType;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.run.RunStatus;
import com.databridge.core.schema.TableSchema;
import com.databridge.core.service.AuditLog;
import com.databridge.core.service.IntakeResult;
import com.databridge.core.service.IntakeService;
import com.databridge.core.service.MigrationService;
import com.databridge.core.service.NotFoundException;
import com.databridge.core.service.RetentionService;
import com.databridge.core.service.SetupBundle;
import com.databridge.core.service.SetupService;
import com.databridge.core.service.ValidationException;
import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.SheetSetup;
import com.databridge.core.setup.TemplateSetup;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.RoutingHandler;
import io.undertow.util.Methods;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** The REST API: URL templates mapped to core service calls. No business logic lives here. */
public final class ApiRoutes {

    public record NewClient(String code, String name) { }
    public record NewProject(String code, String name, String bcCompany) { }
    public record NewRun(RunType type, LocalDate cutoffDate) { }

    /** A dataset version without its (large) schema JSON. */
    public record DatasetView(String id, String runId, TemplateKind kind, int versionNo, String fileName, String sha256,
                              long sizeBytes, int sheetCount, int warningCount, RunStatus status, String error,
                              Instant uploadedAt, String uploadedBy) {
        static DatasetView of(DatasetVersion d) {
            return new DatasetView(d.id(), d.runId(), d.kind(), d.versionNo(), d.fileName(), d.sha256(), d.sizeBytes(),
                    d.sheetCount(), d.warningCount(), d.status(), d.error(), d.uploadedAt(), d.uploadedBy());
        }
    }

    public record SheetSummary(String sheetName, String tableName, Integer tableId, int captionRow, int columns,
                               long typedColumns, List<String> warnings) {
        static SheetSummary of(TableSchema t) {
            return new SheetSummary(t.sheetName(), t.tableName(), t.tableId(), t.captionRow(), t.fields().size(),
                    t.typedFieldCount(), t.warnings());
        }
    }

    public record IntakeResponse(DatasetView dataset, List<SheetSummary> sheets) { }

    public record Health(String status, String database) { }

    /** Checks the database; returns true when it answers. */
    @FunctionalInterface
    public interface DatabaseProbe { boolean ok(); }

    private final SetupService setups;
    private final MigrationService migrations;
    private final IntakeService intake;
    private final RetentionService retention;
    private final AuditLog audit;
    private final DatabaseProbe probe;
    private final ObjectMapper mergeMapper;

    public ApiRoutes(SetupService setups, MigrationService migrations, IntakeService intake, RetentionService retention,
                     AuditLog audit, DatabaseProbe probe, ObjectMapper json) {
        this.setups = setups;
        this.migrations = migrations;
        this.intake = intake;
        this.retention = retention;
        this.audit = audit;
        this.probe = probe;
        this.mergeMapper = json.copy()
                .setDefaultMergeable(true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public RoutingHandler build(Endpoints e) {
        RoutingHandler r = new RoutingHandler();

        r.get("/health", e.handler(this::health));

        // Setups
        r.get("/api/setup", e.handler(q -> Response.ok(setups.effective().general())));
        r.add(Methods.PATCH, "/api/setup", e.handler(this::patchSetup));
        r.get("/api/setup/templates", e.handler(q -> Response.ok(setups.effective().templates().all())));
        r.put("/api/setup/templates/{sheetName}", e.handler(this::putTemplate));
        r.delete("/api/setup/templates/{sheetName}", e.handler(q ->
                setups.deleteSheet(q.path("sheetName"), q.actor()) ? Response.noContent() : Response.notFound()));
        r.get("/api/setup/export", e.handler(q -> Response.ok(setups.stored())));
        r.post("/api/setup/import", e.handler(q -> {
            setups.importBundle(q.body(SetupBundle.class), q.actor());
            return Response.noContent();
        }));

        // Clients, projects, runs
        r.get("/api/clients", e.handler(q -> Response.ok(migrations.clients())));
        r.post("/api/clients", e.handler(q -> {
            NewClient b = q.body(NewClient.class);
            return Response.created(migrations.createClient(b.code(), b.name(), q.actor()));
        }));
        r.get("/api/clients/{clientId}/projects", e.handler(q -> Response.ok(migrations.projects(q.path("clientId")))));
        r.post("/api/clients/{clientId}/projects", e.handler(q -> {
            NewProject b = q.body(NewProject.class);
            return Response.created(migrations.createProject(q.path("clientId"), b.code(), b.name(), b.bcCompany(), q.actor()));
        }));
        r.get("/api/projects/{projectId}/runs", e.handler(q -> Response.ok(migrations.runs(q.path("projectId")))));
        r.post("/api/projects/{projectId}/runs", e.handler(q -> {
            NewRun b = q.body(NewRun.class);
            return Response.created(migrations.openRun(q.path("projectId"), b.type(), b.cutoffDate(), q.actor()));
        }));
        r.get("/api/runs/{runId}", e.handler(q -> Response.ok(migrations.run(q.path("runId")))));
        r.post("/api/runs/{runId}/close", e.handler(q -> Response.ok(migrations.closeRun(q.path("runId"), q.actor()))));

        // Datasets
        r.post("/api/runs/{runId}/datasets", e.handler(this::upload));
        r.get("/api/runs/{runId}/datasets", e.handler(q ->
                Response.ok(intake.datasets(q.path("runId")).stream().map(DatasetView::of).toList())));
        r.get("/api/datasets/{id}", e.handler(q -> Response.ok(DatasetView.of(intake.dataset(q.path("id"))))));
        r.get("/api/datasets/{id}/schema", e.handler(q -> {
            DatasetVersion d = intake.dataset(q.path("id"));
            if (d.schemaJson() == null) throw new NotFoundException("Schema for dataset", d.id());
            return Response.rawJson(d.schemaJson());
        }));

        // Retention and audit
        r.post("/api/retention/purge", e.handler(this::purge));
        r.get("/api/audit", e.handler(q -> Response.ok(audit.list(q.query("entityType"), q.query("entityId"),
                integer(q.query("limit"), 100)))));

        r.setFallbackHandler(e.status(404, "No such endpoint"));
        r.setInvalidMethodHandler(e.status(405, "Method not allowed on this endpoint"));
        return r;
    }

    private Response health(Request q) {
        boolean db = probe.ok();
        return Response.of(db ? 200 : 503, new Health(db ? "UP" : "DOWN", db ? "UP" : "DOWN"));
    }

    /** Changes only the values named in the body; everything else is kept. */
    private Response patchSetup(Request q) throws Exception {
        DataBridgeSetup current = setups.effective().general();
        mergeMapper.readerForUpdating(current).readValue(q.bodyTree());
        return Response.ok(setups.updateGeneral(current, q.actor()));
    }

    private Response putTemplate(Request q) throws Exception {
        String sheetName = q.path("sheetName");
        SheetSetup body = q.body(SheetSetup.class);
        if (body.getSheetName() == null) {
            body.setSheetName(sheetName);
        } else if (!TemplateSetup.normalise(body.getSheetName()).equals(TemplateSetup.normalise(sheetName))) {
            throw new ValidationException("sheetName in the body does not match the URL");
        }
        return Response.ok(setups.putSheet(body, q.actor()));
    }

    /**
     * The workbook is the raw request body (Content-Type: application/octet-stream), streamed
     * straight into a workspace: no multipart parsing, so no server-side temporary copy.
     * 201 when the workbook was read; 422 with the recorded ERROR version when it could not be.
     */
    private Response upload(Request q) {
        TemplateKind kind;
        try {
            kind = TemplateKind.valueOf(q.requiredQuery("kind").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("kind must be STATIC, TRANSACTIONAL, HR or OTHER");
        }
        IntakeResult r = intake.intake(q.path("runId"), kind, q.requiredQuery("fileName"), q.stream(), q.actor());
        List<SheetSummary> sheets = r.schema() == null ? List.of()
                : r.schema().sheets().stream().map(SheetSummary::of).toList();
        return Response.of(r.succeeded() ? 201 : 422, new IntakeResponse(DatasetView.of(r.dataset()), sheets));
    }

    private Response purge(Request q) {
        String scope = q.query("scope") == null ? "expired" : q.query("scope");
        switch (scope) {
            case "all": return Response.ok(retention.purgeAll(q.actor()));
            case "expired": return Response.ok(retention.sweep());
            default: throw new ValidationException("scope must be 'all' or 'expired'");
        }
    }

    private static int integer(String v, int def) {
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException("'" + v + "' is not a whole number");
        }
    }
}
