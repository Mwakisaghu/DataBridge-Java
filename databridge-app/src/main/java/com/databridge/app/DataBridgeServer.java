package com.databridge.app;

import com.databridge.app.http.Endpoints;
import com.databridge.app.http.Json;
import com.databridge.core.service.AuditLog;
import com.databridge.core.service.FindingService;
import com.databridge.core.service.IntakeService;
import com.databridge.core.service.RuleService;
import com.databridge.core.service.MigrationService;
import com.databridge.core.service.RetentionService;
import com.databridge.core.service.SetupService;
import com.databridge.core.workspace.WorkspaceManagers;
import com.databridge.store.jdbc.JdbcRepositories;
import com.databridge.store.jdbc.JdbcValidationRepositories;
import com.databridge.store.jdbc.SchemaMigrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.undertow.Undertow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * DataBridge server: Undertow over the core engine and the JDBC store, wired by hand.
 * Startup order: connection pool, schema migration, services, startup sweep, HTTP, schedule.
 */
public final class DataBridgeServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DataBridgeServer.class);

    private final HikariDataSource dataSource;
    private final Undertow undertow;
    private final ScheduledExecutorService scheduler;

    private DataBridgeServer(HikariDataSource dataSource, Undertow undertow, ScheduledExecutorService scheduler) {
        this.dataSource = dataSource;
        this.undertow = undertow;
        this.scheduler = scheduler;
    }

    public static void main(String[] args) {
        AppConfig config;
        try {
            config = AppConfig.load(args, System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("databridge: " + e.getMessage());
            System.exit(1);
            return;
        }
        DataBridgeServer server;
        try {
            server = start(config);
        } catch (RuntimeException e) {
            System.err.println("databridge: " + startupProblem(e, config));
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "databridge-shutdown"));
        System.out.println("DataBridge listening on http://" + config.host() + ":" + server.port()
                + "  (database " + config.safeDbUrl() + ")");
    }

    public static DataBridgeServer start(AppConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.dbUrl());
        hc.setUsername(config.dbUser());
        hc.setPassword(config.dbPassword());
        hc.setMaximumPoolSize(10);
        hc.setPoolName("databridge");
        HikariDataSource ds = new HikariDataSource(hc);
        try {
            List<SchemaMigrator.Applied> applied = new SchemaMigrator(ds).migrate();
            applied.forEach(a -> LOG.info("Applied database migration {}", a.script()));

            Clock clock = Clock.systemUTC();
            JdbcRepositories repos = new JdbcRepositories(ds);
            AuditLog audit = new AuditLog(repos.audit(), clock);
            SetupService setups = new SetupService(repos.setups(), audit, clock);
            MigrationService migrations = new MigrationService(repos.clients(), repos.projects(), repos.runs(), audit, clock);
            WorkspaceManagers workspaces = new WorkspaceManagers();
            JdbcValidationRepositories vrepos = new JdbcValidationRepositories(ds);
            RuleService rules = new RuleService(vrepos.rules(), audit, clock);
            IntakeService intake = new IntakeService(migrations, repos.datasets(), setups, rules, vrepos.checks(),
                    vrepos.findings(), workspaces, audit, clock);
            FindingService findings = new FindingService(vrepos.checks(), vrepos.findings(), migrations, audit, clock);
            RetentionService retention = new RetentionService(setups, workspaces, audit);
            Services services = new Services(setups, migrations, intake, retention, audit, rules, findings);

            if (retention.sweepOnStartup()) sweep(retention);

            ObjectMapper json = Json.mapper();
            ApiRoutes routes = new ApiRoutes(services, () -> ping(ds), json);
            Undertow undertow = Undertow.builder()
                    .addHttpListener(config.port(), config.host())
                    .setHandler(routes.build(new Endpoints(json)))
                    .build();
            undertow.start();

            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "databridge-retention-sweep");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(() -> {
                        intake.sweepSubmissions();
                        sweep(retention);
                    },
                    config.sweepIntervalMinutes(), config.sweepIntervalMinutes(), TimeUnit.MINUTES);
            return new DataBridgeServer(ds, undertow, scheduler);
        } catch (RuntimeException e) {
            ds.close();
            throw e;
        }
    }

    /** A one-line explanation of why startup failed, with what to do about it. */
    static String startupProblem(Throwable e, AppConfig config) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.BindException) {
                return "port " + config.port() + " on " + config.host() + " is already in use. Stop the other process "
                        + "(is DataBridge already running?) or set DATABRIDGE_PORT to another port.";
            }
            if (t instanceof com.databridge.store.jdbc.MigrationException) {
                return "database schema problem: " + t.getMessage();
            }
            if (t instanceof java.sql.SQLException) {
                return "cannot connect to the database " + config.safeDbUrl() + ": " + t.getMessage();
            }
        }
        return "could not start: " + e;
    }

    /** The port actually bound (useful when configured as 0). */
    public int port() {
        return ((InetSocketAddress) undertow.getListenerInfo().get(0).getAddress()).getPort();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        undertow.stop();
        dataSource.close();
    }

    private static void sweep(RetentionService retention) {
        try {
            retention.sweep();
        } catch (RuntimeException e) {
            LOG.error("Workspace sweep failed: {}", e.getMessage());
        }
    }

    private static boolean ping(HikariDataSource ds) {
        try (Connection c = ds.getConnection()) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}
