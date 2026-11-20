package com.databridge.core.port.memory;

import com.databridge.core.domain.AuditEvent;
import com.databridge.core.domain.FindingCount;
import com.databridge.core.domain.FindingFilter;
import com.databridge.core.domain.StoredFinding;
import com.databridge.core.domain.ValidationCheck;
import com.databridge.core.finding.FindingStatus;
import com.databridge.core.finding.Severity;
import com.databridge.core.port.CheckRepository;
import com.databridge.core.port.FindingRepository;
import com.databridge.core.port.RuleRepository;
import com.databridge.core.port.UserRepository;
import com.databridge.core.security.Session;
import com.databridge.core.security.User;
import com.databridge.core.setup.SetupLoader;
import com.databridge.core.validation.RuleDef;
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

    public static final class Checks implements CheckRepository {
        private final Map<String, ValidationCheck> rows = new LinkedHashMap<>();
        @Override public synchronized void insert(ValidationCheck c) { rows.put(c.id(), c); }
        @Override public synchronized Optional<ValidationCheck> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public synchronized List<ValidationCheck> listByRun(String runId) {
            return rows.values().stream().filter(c -> c.runId().equals(runId))
                    .sorted(Comparator.comparingInt(ValidationCheck::checkNo).reversed()).toList();
        }
        @Override public synchronized int maxCheckNo(String runId) {
            return rows.values().stream().filter(c -> c.runId().equals(runId)).mapToInt(ValidationCheck::checkNo).max().orElse(0);
        }
    }

    public static final class Findings implements FindingRepository {
        private final Map<String, StoredFinding> rows = new LinkedHashMap<>();
        private static final Comparator<StoredFinding> ORDER = Comparator.comparing(StoredFinding::severity)
                .thenComparing(f -> String.valueOf(f.sheet())).thenComparingInt(StoredFinding::rowNo).thenComparing(StoredFinding::id);
        @Override public synchronized void insertAll(List<StoredFinding> fs) { fs.forEach(f -> rows.put(f.id(), f)); }
        @Override public synchronized Optional<StoredFinding> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public synchronized void update(StoredFinding f) {
            if (!rows.containsKey(f.id())) throw new IllegalStateException("No finding " + f.id());
            rows.put(f.id(), f);
        }
        @Override public synchronized List<StoredFinding> list(String checkId, FindingFilter filter, int offset, int limit) {
            return rows.values().stream().filter(f -> f.checkId().equals(checkId) && filter.matches(f)).sorted(ORDER)
                    .skip(offset).limit(limit).toList();
        }
        @Override public synchronized int count(String checkId, FindingFilter filter) {
            return (int) rows.values().stream().filter(f -> f.checkId().equals(checkId) && filter.matches(f)).count();
        }
        @Override public synchronized List<FindingCount> counts(String checkId) {
            Map<String, Integer> n = new LinkedHashMap<>();
            rows.values().stream().filter(f -> f.checkId().equals(checkId))
                    .forEach(f -> n.merge(f.severity() + "|" + f.status(), 1, Integer::sum));
            List<FindingCount> out = new ArrayList<>();
            n.forEach((k, v) -> {
                String[] p = k.split("\\|");
                out.add(new FindingCount(Severity.valueOf(p[0]), FindingStatus.valueOf(p[1]), v));
            });
            return out;
        }
    }

    public static final class Rules implements RuleRepository {
        private final Map<String, String> rows = new LinkedHashMap<>();
        @Override public synchronized List<RuleDef> list() {
            List<RuleDef> out = new ArrayList<>();
            for (String json : rows.values()) {
                try {
                    out.add(SetupLoader.mapper().readValue(json, RuleDef.class));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return out;
        }
        @Override public synchronized void save(RuleDef r, String actor, Instant at) {
            try {
                rows.put(r.getId(), SetupLoader.mapper().writeValueAsString(r));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        @Override public synchronized boolean delete(String id) { return rows.remove(id) != null; }
    }

    public static final class Recon implements com.databridge.core.port.ReconRepository {
        private final Map<String, String> controls = new LinkedHashMap<>();
        private String trialBalance;
        private final Map<String, List<com.databridge.core.recon.ReconLine>> lines = new LinkedHashMap<>();

        private static String json(Object o) {
            try {
                return SetupLoader.mapper().writeValueAsString(o);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        private static <T> T read(String json, Class<T> type) {
            try {
                return SetupLoader.mapper().readValue(json, type);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        @Override public synchronized List<com.databridge.core.recon.ControlDef> controls() {
            return controls.values().stream().map(j -> read(j, com.databridge.core.recon.ControlDef.class)).toList();
        }
        @Override public synchronized void saveControl(com.databridge.core.recon.ControlDef c, String actor, Instant at) {
            controls.put(c.getId(), json(c));
        }
        @Override public synchronized boolean deleteControl(String id) { return controls.remove(id) != null; }
        @Override public synchronized Optional<com.databridge.core.recon.TrialBalanceDef> trialBalance() {
            return Optional.ofNullable(trialBalance).map(j -> read(j, com.databridge.core.recon.TrialBalanceDef.class));
        }
        @Override public synchronized void saveTrialBalance(com.databridge.core.recon.TrialBalanceDef tb, String actor, Instant at) {
            trialBalance = json(tb);
        }
        @Override public synchronized boolean deleteTrialBalance() {
            boolean had = trialBalance != null;
            trialBalance = null;
            return had;
        }
        @Override public synchronized void insertLines(String checkId, String runId, List<com.databridge.core.recon.ReconLine> ls) {
            lines.put(checkId, List.copyOf(ls));
        }
        @Override public synchronized List<com.databridge.core.recon.ReconLine> lines(String checkId) {
            return lines.getOrDefault(checkId, List.of());
        }
    }

    public static final class Users implements UserRepository {
        private final Map<String, User> rows = new LinkedHashMap<>();
        private final Map<String, Session> sessions = new LinkedHashMap<>();
        @Override public synchronized void insert(User u) {
            if (findByUsername(u.username()).isPresent()) throw new IllegalStateException("Duplicate user " + u.username());
            rows.put(u.id(), u);
        }
        @Override public synchronized void update(User u) {
            if (!rows.containsKey(u.id())) throw new IllegalStateException("No user " + u.id());
            rows.put(u.id(), u);
        }
        @Override public synchronized Optional<User> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public synchronized Optional<User> findByUsername(String n) {
            return rows.values().stream().filter(u -> u.username().equals(n)).findFirst();
        }
        @Override public synchronized List<User> list() {
            return rows.values().stream().sorted(Comparator.comparing(User::username)).toList();
        }
        @Override public synchronized int count() { return rows.size(); }
        @Override public synchronized void insertSession(Session s) { sessions.put(s.tokenHash(), s); }
        @Override public synchronized Optional<Session> findSession(String h) { return Optional.ofNullable(sessions.get(h)); }
        @Override public synchronized void touchSession(String h, Instant at) {
            Session s = sessions.get(h);
            if (s != null) sessions.put(h, new Session(h, s.userId(), s.createdAt(), s.expiresAt(), at));
        }
        @Override public synchronized void deleteSession(String h) { sessions.remove(h); }
        @Override public synchronized void deleteSessionsOf(String userId) { sessions.values().removeIf(s -> s.userId().equals(userId)); }
        @Override public synchronized int deleteExpiredSessions(Instant now) {
            int before = sessions.size();
            sessions.values().removeIf(s -> !s.expiresAt().isAfter(now));
            return before - sessions.size();
        }
    }
}
