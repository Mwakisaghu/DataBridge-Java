package com.databridge.core.service;

import com.databridge.core.port.AuditRepository;
import com.databridge.core.port.CheckRepository;
import com.databridge.core.port.FindingRepository;
import com.databridge.core.port.RuleRepository;
import com.databridge.core.port.ReconRepository;
import com.databridge.core.port.ClientRepository;
import com.databridge.core.port.DatasetRepository;
import com.databridge.core.port.ProjectRepository;
import com.databridge.core.port.RunRepository;
import com.databridge.core.port.SetupRepository;
import com.databridge.core.port.memory.InMemoryRepositories;
import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.workspace.WorkspaceManagers;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Wires the services over a given set of repositories, with workspaces under a temp folder. */
public final class Fixture {

    public final SetupRepository setupRepo;
    public final AuditRepository auditRepo;
    public final Clock clock = Clock.fixed(Instant.parse("2027-01-31T08:00:00Z"), ZoneOffset.UTC);
    public final AuditLog audit;
    public final SetupService setups;
    public final MigrationService migrations;
    public final IntakeService intake;
    public final RetentionService retention;
    public final RuleService rules;
    public final FindingService findings;
    public final ReconciliationService recon;
    public final Path workspaceRoot;

    public Fixture(Path tmp, ClientRepository clients, ProjectRepository projects, RunRepository runs,
                   DatasetRepository datasets, AuditRepository auditRepo, SetupRepository setupRepo) {
        this(tmp, clients, projects, runs, datasets, auditRepo, setupRepo, new InMemoryRepositories.Checks(),
                new InMemoryRepositories.Findings(), new InMemoryRepositories.Rules());
    }

    public Fixture(Path tmp, ClientRepository clients, ProjectRepository projects, RunRepository runs,
                   DatasetRepository datasets, AuditRepository auditRepo, SetupRepository setupRepo,
                   CheckRepository checks, FindingRepository findingRepo, RuleRepository ruleRepo) {
        this(tmp, clients, projects, runs, datasets, auditRepo, setupRepo, checks, findingRepo, ruleRepo,
                new InMemoryRepositories.Recon());
    }

    public Fixture(Path tmp, ClientRepository clients, ProjectRepository projects, RunRepository runs,
                   DatasetRepository datasets, AuditRepository auditRepo, SetupRepository setupRepo,
                   CheckRepository checks, FindingRepository findingRepo, RuleRepository ruleRepo,
                   ReconRepository reconRepo) {
        this.setupRepo = setupRepo;
        this.auditRepo = auditRepo;
        this.audit = new AuditLog(auditRepo, clock);
        this.setups = new SetupService(setupRepo, audit, clock);
        this.migrations = new MigrationService(clients, projects, runs, audit, clock);
        WorkspaceManagers wm = new WorkspaceManagers();
        this.rules = new RuleService(ruleRepo, audit, clock);
        this.recon = new ReconciliationService(reconRepo, checks, findingRepo, migrations, audit, clock);
        this.intake = new IntakeService(migrations, datasets, setups, rules, checks, findingRepo, wm, audit, clock, recon);
        this.findings = new FindingService(checks, findingRepo, migrations, audit, clock);
        this.retention = new RetentionService(setups, wm, audit);
        this.workspaceRoot = tmp.resolve("workspaces");
        DataBridgeSetup g = setups.effective().general();
        g.setWorkspaceDir(workspaceRoot.toString());
        setups.updateGeneral(g, "test-setup");
    }

    public static Fixture inMemory(Path tmp) {
        return new Fixture(tmp, new InMemoryRepositories.Clients(), new InMemoryRepositories.Projects(),
                new InMemoryRepositories.Runs(), new InMemoryRepositories.Datasets(),
                new InMemoryRepositories.Audit(), new InMemoryRepositories.Setups());
    }
}
