package com.databridge.core.service;

import com.databridge.core.port.AuditRepository;
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
    public final Path workspaceRoot;

    public Fixture(Path tmp, ClientRepository clients, ProjectRepository projects, RunRepository runs,
                   DatasetRepository datasets, AuditRepository auditRepo, SetupRepository setupRepo) {
        this.setupRepo = setupRepo;
        this.auditRepo = auditRepo;
        this.audit = new AuditLog(auditRepo, clock);
        this.setups = new SetupService(setupRepo, audit, clock);
        this.migrations = new MigrationService(clients, projects, runs, audit, clock);
        WorkspaceManagers wm = new WorkspaceManagers();
        this.intake = new IntakeService(migrations, datasets, setups, wm, audit, clock);
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
