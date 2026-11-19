package com.databridge.app;

import com.databridge.app.http.Access;
import com.databridge.app.http.Endpoints;
import com.databridge.app.http.Request;
import com.databridge.app.http.Response;
import com.databridge.core.domain.FindingCount;
import com.databridge.core.domain.FindingFilter;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.domain.ValidationCheck;
import com.databridge.core.finding.FindingStatus;
import com.databridge.core.finding.Severity;
import com.databridge.core.run.RunStatus;
import com.databridge.core.service.IntakeResult;
import com.databridge.core.service.Submission;
import com.databridge.core.service.SubmissionResult;
import com.databridge.core.service.ValidationException;
import com.databridge.core.validation.RuleDef;
import com.databridge.core.validation.SheetStat;
import com.databridge.core.validation.SkippedRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.RoutingHandler;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Module 2: submissions, validation checks, the exception register, waivers and the Rule Setup. */
public final class ValidationRoutes {

    /** A check with its per-sheet quality, skipped rules and finding counts by severity and status. */
    public record CheckView(String id, String runId, int checkNo, RunStatus status, int rows, int critical, int warning,
                            int info, BigDecimal readiness, String error, Instant createdAt, String createdBy,
                            List<String> datasetIds, List<SheetStat> sheets, List<SkippedRule> skipped,
                            List<FindingCount> counts) { }

    public record ProcessResponse(List<ApiRoutes.IntakeResponse> datasets, CheckView check) { }
    public record WaiverRequest(String reason) { }
    public record WaiverDecision(Boolean approve, String note) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Services s;

    public ValidationRoutes(Services services) {
        this.s = services;
    }

    public void register(RoutingHandler r, Endpoints e) {
        r.post("/api/runs/{runId}/submissions", e.handler(Access.CONSULTANT, q -> Response.created(s.intake().openSubmission(q.path("runId"), q.actor()))));
        r.get("/api/submissions/{id}", e.handler(q -> Response.ok(s.intake().submission(q.path("id")))));
        r.post("/api/submissions/{id}/files", e.handler(Access.CONSULTANT, q -> {
            Submission.File f = s.intake().addFile(q.path("id"), kind(q.requiredQuery("kind")), q.requiredQuery("fileName"),
                    q.stream(), q.actor());
            return Response.of(f.error() == null ? 201 : 422, f);
        }));
        r.post("/api/submissions/{id}/process", e.handler(Access.CONSULTANT, q -> {
            SubmissionResult res = s.intake().process(q.path("id"), q.actor());
            CheckView cv = view(res.check());
            List<ApiRoutes.IntakeResponse> ds = res.datasets().stream().map(ValidationRoutes::intakeResponse).toList();
            return Response.of(res.check().status() == RunStatus.ERROR ? 422 : 201, new ProcessResponse(ds, cv));
        }));
        r.delete("/api/submissions/{id}", e.handler(Access.CONSULTANT, q -> {
            s.intake().cancel(q.path("id"), q.actor());
            return Response.noContent();
        }));

        r.get("/api/runs/{runId}/checks", e.handler(q ->
                Response.ok(s.findings().checks(q.path("runId")).stream().map(this::view).toList())));
        r.get("/api/checks/{id}", e.handler(q -> Response.ok(view(s.findings().check(q.path("id"))))));
        r.get("/api/checks/{id}/findings", e.handler(q -> Response.ok(s.findings().page(q.path("id"), filter(q),
                integer(q.query("offset"), 0), integer(q.query("limit"), 100)))));
        r.get("/api/checks/{id}/register.xlsx", e.handler(q -> {
            ValidationCheck c = s.findings().check(q.path("id"));
            return Response.file(s.findings().register(c.id()), Response.XLSX, "exception-register-check-" + c.checkNo() + ".xlsx");
        }));
        r.post("/api/findings/{id}/waiver", e.handler(Access.CONSULTANT, q ->
                Response.ok(s.findings().requestWaiver(q.path("id"), q.body(WaiverRequest.class).reason(), q.actor()))));
        r.post("/api/findings/{id}/waiver/decision", e.handler(Access.REVIEWER, q -> {
            WaiverDecision d = q.body(WaiverDecision.class);
            if (d.approve() == null) throw new ValidationException("Say whether the waiver is approved (approve: true or false)");
            return Response.ok(s.findings().decideWaiver(q.path("id"), d.approve(), d.note(), q.actor()));
        }));

        r.get("/api/setup/rules", e.handler(q -> Response.ok(s.rules().views())));
        r.put("/api/setup/rules/{id}", e.handler(Access.ADMIN, q -> {
            RuleDef rule = q.body(RuleDef.class);
            if (rule.getId() == null) rule.setId(q.path("id"));
            else if (!rule.getId().equals(q.path("id"))) throw new ValidationException("Rule id in the body does not match the URL");
            return Response.ok(s.rules().put(rule, q.actor()));
        }));
        r.delete("/api/setup/rules/{id}", e.handler(Access.ADMIN, q ->
                s.rules().delete(q.path("id"), q.actor()) ? Response.noContent() : Response.notFound()));
    }

    CheckView view(ValidationCheck c) {
        try {
            return new CheckView(c.id(), c.runId(), c.checkNo(), c.status(), c.rows(), c.critical(), c.warning(), c.info(),
                    c.readiness(), c.error(), c.createdAt(), c.createdBy(),
                    c.datasetIds() == null || c.datasetIds().isEmpty() ? List.of() : Arrays.asList(c.datasetIds().split(",")),
                    List.of(JSON.readValue(c.sheetsJson() == null ? "[]" : c.sheetsJson(), SheetStat[].class)),
                    List.of(JSON.readValue(c.skippedJson() == null ? "[]" : c.skippedJson(), SkippedRule[].class)),
                    s.findings().counts(c.id()));
        } catch (IOException ex) {
            throw new IllegalStateException("Stored check " + c.id() + " cannot be read", ex);
        }
    }

    static ApiRoutes.IntakeResponse intakeResponse(IntakeResult r) {
        List<ApiRoutes.SheetSummary> sheets = r.schema() == null ? List.of()
                : r.schema().sheets().stream().map(ApiRoutes.SheetSummary::of).toList();
        return new ApiRoutes.IntakeResponse(ApiRoutes.DatasetView.of(r.dataset()), sheets, null);
    }

    static TemplateKind kind(String k) {
        try {
            return TemplateKind.valueOf(k.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("kind must be STATIC, TRANSACTIONAL, HR or OTHER");
        }
    }

    private static FindingFilter filter(Request q) {
        return new FindingFilter(enumOf(Severity.class, q.query("severity")), enumOf(FindingStatus.class, q.query("status")),
                blankToNull(q.query("sheet")), blankToNull(q.query("ruleType")));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("'" + v + "' is not a valid " + type.getSimpleName());
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static int integer(String v, int def) {
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            throw new ValidationException("'" + v + "' is not a whole number");
        }
    }
}
