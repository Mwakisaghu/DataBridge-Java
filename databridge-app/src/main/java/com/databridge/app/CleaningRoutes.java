package com.databridge.app;

import com.databridge.app.http.Access;
import com.databridge.app.http.Endpoints;
import com.databridge.app.http.Response;
import com.databridge.core.cleaning.CleanRule;
import com.databridge.core.service.CleaningService;
import com.databridge.core.service.ValidationException;
import io.undertow.server.RoutingHandler;

/** Module 3: the Cleaning Setup, clean-and-download and the cleaning change logs. */
public final class CleaningRoutes {

    /** Header carrying the id of the cleaning job that produced a cleaned workbook. */
    public static final String JOB_HEADER = "X-DataBridge-Cleaning-Job";

    private final CleaningService cleaning;

    public CleaningRoutes(CleaningService cleaning) {
        this.cleaning = cleaning;
    }

    public void register(RoutingHandler r, Endpoints e) {
        r.post("/api/runs/{runId}/clean", e.handler(Access.CONSULTANT, q -> {
            CleaningService.Output out = cleaning.clean(q.path("runId"), ValidationRoutes.kind(q.requiredQuery("kind")),
                    q.requiredQuery("fileName"), q.stream(), q.actor());
            return Response.file(out.workbook(), Response.XLSX, out.fileName())
                    .withHeader(JOB_HEADER, out.job().id())
                    .withHeader("Access-Control-Expose-Headers", JOB_HEADER);
        }));
        r.get("/api/runs/{runId}/cleaning-jobs", e.handler(q -> Response.ok(cleaning.jobs(q.path("runId")))));
        r.get("/api/cleaning-jobs/{id}", e.handler(q -> Response.ok(cleaning.job(q.path("id")))));

        r.get("/api/setup/cleaning", e.handler(q -> Response.ok(cleaning.views())));
        r.put("/api/setup/cleaning/{id}", e.handler(Access.ADMIN, q -> {
            CleanRule rule = q.body(CleanRule.class);
            if (rule.getId() == null) rule.setId(q.path("id"));
            else if (!rule.getId().equals(q.path("id"))) throw new ValidationException("Rule id in the body does not match the URL");
            return Response.ok(cleaning.put(rule, q.actor()));
        }));
        r.delete("/api/setup/cleaning/{id}", e.handler(Access.ADMIN, q ->
                cleaning.delete(q.path("id"), q.actor()) ? Response.noContent() : Response.notFound()));
    }
}
