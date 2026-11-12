package com.databridge.core.service;

import com.databridge.core.domain.Client;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.RunState;
import com.databridge.core.domain.RunType;
import com.databridge.core.port.ClientRepository;
import com.databridge.core.port.ProjectRepository;
import com.databridge.core.port.RunRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Clients, projects and migration runs (module 1's backbone). */
public final class MigrationService {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,19}$");

    private final ClientRepository clients;
    private final ProjectRepository projects;
    private final RunRepository runs;
    private final AuditLog audit;
    private final Clock clock;

    public MigrationService(ClientRepository clients, ProjectRepository projects, RunRepository runs,
                            AuditLog audit, Clock clock) {
        this.clients = clients;
        this.projects = projects;
        this.runs = runs;
        this.audit = audit;
        this.clock = clock;
    }

    public Client createClient(String code, String name, String actor) {
        String who = AuditLog.actor(actor);
        String c = code(code);
        String n = required(name, "name", 100);
        if (clients.findByCode(c).isPresent()) throw new ValidationException("Client code " + c + " already exists");
        Client client = new Client(UUID.randomUUID().toString(), c, n, now(), who);
        clients.insert(client);
        audit.record(who, "CLIENT_CREATED", "CLIENT", client.id(), c);
        return client;
    }

    public Project createProject(String clientId, String code, String name, String bcCompany, String actor) {
        String who = AuditLog.actor(actor);
        Client client = client(clientId);
        String c = code(code);
        String n = required(name, "name", 100);
        String company = bcCompany == null || bcCompany.isBlank() ? null : required(bcCompany, "bcCompany", 100);
        if (projects.findByCode(client.id(), c).isPresent()) {
            throw new ValidationException("Project code " + c + " already exists for " + client.code());
        }
        Project p = new Project(UUID.randomUUID().toString(), client.id(), c, n, company, now(), who);
        projects.insert(p);
        audit.record(who, "PROJECT_CREATED", "PROJECT", p.id(), client.code() + "/" + c);
        return p;
    }

    public MigrationRun openRun(String projectId, RunType type, LocalDate cutoffDate, String actor) {
        String who = AuditLog.actor(actor);
        Project p = project(projectId);
        if (type == null) throw new ValidationException("Run type is required (MOCK or CUTOVER)");
        if (cutoffDate == null) throw new ValidationException("Cutoff date is required");
        int no = runs.maxRunNo(p.id()) + 1;
        MigrationRun run = new MigrationRun(UUID.randomUUID().toString(), p.id(), no, type, cutoffDate,
                RunState.OPEN, now(), who);
        runs.insert(run);
        audit.record(who, "RUN_OPENED", "RUN", run.id(), type + " run " + no + ", cutoff " + cutoffDate);
        return run;
    }

    public MigrationRun closeRun(String runId, String actor) {
        String who = AuditLog.actor(actor);
        MigrationRun run = run(runId);
        if (run.state() == RunState.CLOSED) return run;
        MigrationRun closed = run.withState(RunState.CLOSED);
        runs.updateState(closed);
        audit.record(who, "RUN_CLOSED", "RUN", run.id(), "Run " + run.runNo());
        return closed;
    }

    public List<Client> clients() { return clients.list(); }
    public List<Project> projects(String clientId) { return projects.listByClient(client(clientId).id()); }
    public List<MigrationRun> runs(String projectId) { return runs.listByProject(project(projectId).id()); }

    public Client client(String id) {
        return clients.find(id).orElseThrow(() -> new NotFoundException("Client", id));
    }

    public Project project(String id) {
        return projects.find(id).orElseThrow(() -> new NotFoundException("Project", id));
    }

    public MigrationRun run(String id) {
        return runs.find(id).orElseThrow(() -> new NotFoundException("Run", id));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String code(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(c).matches()) {
            throw new ValidationException("Code must be 2-20 characters: letters, digits, - or _");
        }
        return c;
    }

    private static String required(String v, String field, int max) {
        if (v == null || v.isBlank()) throw new ValidationException(field + " is required");
        String t = v.trim();
        if (t.length() > max) throw new ValidationException(field + " is longer than " + max + " characters");
        return t;
    }
}
