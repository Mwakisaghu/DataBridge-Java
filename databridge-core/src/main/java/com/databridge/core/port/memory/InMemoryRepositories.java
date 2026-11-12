package com.databridge.core.port.memory;

import com.databridge.core.domain.AuditEvent;
import com.databridge.core.domain.Client;
import com.databridge.core.domain.DatasetVersion;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.port.AuditRepository;
import com.databridge.core.port.ClientRepository;
import com.databridge.core.port.DatasetRepository;
import com.databridge.core.port.ProjectRepository;
import com.databridge.core.port.RunRepository;
import com.databridge.core.port.SetupRepository;
import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.SheetSetup;
import com.databridge.core.setup.TemplateSetup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Thread-safe in-memory repositories for tests, the CLI and local experiments. */
public final class InMemoryRepositories {

    private InMemoryRepositories() { }

    public static final class Clients implements ClientRepository {
        private final Map<String, Client> rows = new LinkedHashMap<>();
        @Override public synchronized void insert(Client c) {
            if (findByCode(c.code()).isPresent()) throw new IllegalStateException("Duplicate client code " + c.code());
            rows.put(c.id(), c);
        }
        @Override public synchronized Optional<Client> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public synchronized Optional<Client> findByCode(String code) {
            return rows.values().stream().filter(c -> c.code().equals(code)).findFirst();
        }
        @Override public synchronized List<Client> list() {
            return rows.values().stream().sorted(Comparator.comparing(Client::code)).toList();
        }
    }

    public static final class Projects implements ProjectRepository {
        private final Map<String, Project> rows = new LinkedHashMap<>();
        @Override public synchronized void insert(Project p) {
            if (findByCode(p.clientId(), p.code()).isPresent()) throw new IllegalStateException("Duplicate project code " + p.code());
            rows.put(p.id(), p);
        }
        @Override public synchronized Optional<Project> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public synchronized Optional<Project> findByCode(String clientId, String code) {
            return rows.values().stream().filter(p -> p.clientId().equals(clientId) && p.code().equals(code)).findFirst();
        }
        @Override public synchronized List<Project> listByClient(String clientId) {
            return rows.values().stream().filter(p -> p.clientId().equals(clientId))
                    .sorted(Comparator.comparing(Project::code)).toList();
        }
    }

    public static final class Runs implements RunRepository {
        private final Map<String, MigrationRun> rows = new LinkedHashMap<>();
        @Override public synchronized void insert(MigrationRun r) { rows.put(r.id(), r); }
        @Override public synchronized void updateState(MigrationRun r) {
            if (!rows.containsKey(r.id())) throw new IllegalStateException("No run " + r.id());
            rows.put(r.id(), r);
        }
        @Override public synchronized Optional<MigrationRun> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public synchronized List<MigrationRun> listByProject(String projectId) {
            return rows.values().stream().filter(r -> r.projectId().equals(projectId))
                    .sorted(Comparator.comparingInt(MigrationRun::runNo)).toList();
        }
        @Override public synchronized int maxRunNo(String projectId) {
            return rows.values().stream().filter(r -> r.projectId().equals(projectId))
                    .mapToInt(MigrationRun::runNo).max().orElse(0);
        }
    }

    public static final class Datasets implements DatasetRepository {
        private final Map<String, DatasetVersion> rows = new LinkedHashMap<>();
        @Override public synchronized void insert(DatasetVersion d) { rows.put(d.id(), d); }
        @Override public synchronized Optional<DatasetVersion> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public synchronized List<DatasetVersion> listByRun(String runId) {
            return rows.values().stream().filter(d -> d.runId().equals(runId))
                    .sorted(Comparator.comparing(DatasetVersion::kind).thenComparing(DatasetVersion::versionNo, Comparator.reverseOrder()))
                    .toList();
        }
        @Override public synchronized int maxVersion(String runId, TemplateKind kind) {
            return rows.values().stream().filter(d -> d.runId().equals(runId) && d.kind() == kind)
                    .mapToInt(DatasetVersion::versionNo).max().orElse(0);
        }
    }

    public static final class Audit implements AuditRepository {
        private final List<AuditEvent> rows = new ArrayList<>();
        @Override public synchronized void append(AuditEvent e) { rows.add(e); }
        @Override public synchronized List<AuditEvent> list(String entityType, String entityId, int limit) {
            List<AuditEvent> out = new ArrayList<>();
            for (int i = rows.size() - 1; i >= 0 && out.size() < limit; i--) {
                AuditEvent e = rows.get(i);
                if ((entityType == null || entityType.equals(e.entityType()))
                        && (entityId == null || Objects.equals(entityId, e.entityId()))) out.add(e);
            }
            return out;
        }
    }

    public static final class Setups implements SetupRepository {
        private DataBridgeSetup general;
        private final Map<String, SheetSetup> sheets = new LinkedHashMap<>();
        @Override public synchronized Optional<DataBridgeSetup> loadGeneral() { return Optional.ofNullable(general); }
        @Override public synchronized void saveGeneral(DataBridgeSetup s, String actor, Instant at) { general = s; }
        @Override public synchronized List<SheetSetup> loadSheets() { return List.copyOf(sheets.values()); }
        @Override public synchronized void saveSheet(SheetSetup s, String actor, Instant at) {
            sheets.put(TemplateSetup.normalise(s.getSheetName()), s);
        }
        @Override public synchronized boolean deleteSheet(String name) {
            return sheets.remove(TemplateSetup.normalise(name)) != null;
        }
    }
}
