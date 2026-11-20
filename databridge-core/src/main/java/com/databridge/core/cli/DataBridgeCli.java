package com.databridge.core.cli;

import com.databridge.core.run.Outcome;
import com.databridge.core.run.RunStatus;
import com.databridge.core.schema.SchemaExtractor;
import com.databridge.core.schema.SchemaRegistry;
import com.databridge.core.schema.TableSchema;
import com.databridge.core.schema.WorkbookSchema;
import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.EffectiveSetup;
import com.databridge.core.setup.SetupException;
import com.databridge.core.setup.SetupLoader;
import com.databridge.core.workspace.PurgeReport;
import com.databridge.core.workspace.Workspace;
import com.databridge.core.workspace.WorkspaceManager;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command line for the core engine.
 * <pre>
 *   schema &lt;workbook.xlsx&gt;... [--out registry.json] [--setup &lt;dir&gt;]
 *   validate &lt;workbook.xlsx&gt;... [--out register.xlsx] [--setup &lt;dir&gt;]
 *   purge  --expired | --all [--setup &lt;dir&gt;]
 *   setup  [--setup &lt;dir&gt;]
 * </pre>
 * Exit codes: 0 success, 1 usage or setup error, 2 a step ended in ERROR, 3 validation found Critical findings.
 */
public final class DataBridgeCli {

    static final int OK = 0, USAGE = 1, STEP_ERROR = 2, FINDINGS = 3;

    private final PrintStream out;
    private final PrintStream err;

    public DataBridgeCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new DataBridgeCli(System.out, System.err).run(args));
    }

    public int run(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            usage(out);
            return args.length == 0 ? USAGE : OK;
        }
        Args a;
        try {
            a = Args.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("databridge: " + e.getMessage());
            usage(err);
            return USAGE;
        }
        EffectiveSetup setup;
        try {
            setup = SetupLoader.load(a.option("setup") == null ? null : Path.of(a.option("setup")));
        } catch (SetupException e) {
            err.println("databridge: setup error: " + e.getMessage());
            return USAGE;
        }
        switch (a.command) {
            case "schema": return schema(a, setup);
            case "purge": return purge(a, setup);
            case "setup": return showSetup(setup);
            case "validate": return validate(a, setup);
            default:
                err.println("databridge: unknown command '" + a.command + "'");
                usage(err);
                return USAGE;
        }
    }

    private int schema(Args a, EffectiveSetup setup) {
        if (a.positional.isEmpty()) {
            err.println("databridge schema: give at least one workbook");
            return USAGE;
        }
        DataBridgeSetup g = setup.general();
        WorkspaceManager workspaces = new WorkspaceManager(Path.of(g.resolvedWorkspaceDir()), g.getRetention());
        if (g.getRetention().isSweepOnStartup()) workspaces.purgeExpired();
        SchemaExtractor extractor = new SchemaExtractor(setup.templates(), g.getCaptionScanRows());
        SchemaRegistry registry = new SchemaRegistry();
        boolean anyError = false;

        for (String file : a.positional) {
            Path path = Path.of(file);
            Outcome<WorkbookSchema> outcome = Outcome.run("schema " + path.getFileName(), () -> {
                if (!Files.isRegularFile(path)) throw new IllegalArgumentException("file not found: " + file);
                long mb = Files.size(path) / (1024 * 1024);
                if (mb > g.getMaxUploadMb()) {
                    throw new IllegalArgumentException("file is " + mb + " MB; the limit is " + g.getMaxUploadMb() + " MB");
                }
                try (Workspace ws = workspaces.open()) {
                    Path copy = ws.importFile(path);
                    WorkbookSchema s = extractor.extract(copy);
                    return new WorkbookSchema(path.getFileName().toString(), s.sheets());
                }
            });
            if (outcome.status() == RunStatus.ERROR) {
                anyError = true;
                out.println();
                out.println(path.getFileName() + "  ERROR  " + outcome.error().orElse(""));
                continue;
            }
            registry.add(outcome.value());
            print(outcome.value());
        }

        out.println();
        out.printf("%d workbook(s), %d sheet(s) in the schema registry. Client files purged per %s retention.%n",
                registry.workbooks().size(), registry.sheetCount(), g.getRetention().getMode());
        if (a.option("out") != null && !registry.workbooks().isEmpty()) {
            Outcome<Path> written = Outcome.run("write registry", () -> {
                Path target = Path.of(a.option("out"));
                registry.writeJson(target);
                return target;
            });
            if (written.isSuccess()) {
                out.println("Schema registry written to " + written.value());
            } else {
                anyError = true;
                err.println("databridge: " + written.error().orElse(""));
            }
        }
        return anyError ? STEP_ERROR : OK;
    }

    private void print(WorkbookSchema wb) {
        out.println();
        out.println(wb.workbook());
        out.printf("  %-30s %-28s %9s %4s %5s %5s %5s %4s%n",
                "Sheet", "BC table", "Table ID", "Row", "Cols", "Typed", "Opts", "Warn");
        List<String> notes = new ArrayList<>();
        for (TableSchema t : wb.sheets()) {
            out.printf("  %-30s %-28s %9s %4d %5d %5d %5d %4d%n",
                    cut(t.sheetName(), 30),
                    cut(t.tableName() != null ? t.tableName() : t.purpose() != null ? t.purpose() : "-", 28),
                    t.tableId() == null ? "-" : t.tableId().toString(),
                    t.captionRow(), t.fields().size(), t.typedFieldCount(), t.optionFieldCount(), t.warnings().size());
            for (String w : t.warnings()) notes.add(t.sheetName() + ": " + w);
        }
        if (!notes.isEmpty()) {
            out.println("  Warnings:");
            notes.forEach(n -> out.println("   - " + n));
        }
    }

    /**
     * Validates a template set with the shipped Rule Setup, as one submission, without a server or
     * database. The kind of each workbook is taken from its name (static, transactional, hr).
     * Exit code 0 when there is no Critical finding, 3 when there are, 2 on ERROR.
     */
    private int validate(Args a, EffectiveSetup setup) {
        if (a.positional.isEmpty()) {
            err.println("databridge validate: give one to three workbooks");
            return USAGE;
        }
        com.databridge.core.port.memory.InMemoryRepositories.Setups setupRepo = new com.databridge.core.port.memory.InMemoryRepositories.Setups();
        java.time.Clock clock = java.time.Clock.systemUTC();
        com.databridge.core.service.AuditLog audit = new com.databridge.core.service.AuditLog(
                new com.databridge.core.port.memory.InMemoryRepositories.Audit(), clock);
        com.databridge.core.service.SetupService setups = new com.databridge.core.service.SetupService(setupRepo, audit, clock, setup);
        setupRepo.saveGeneral(setup.general(), "cli", clock.instant());
        com.databridge.core.service.MigrationService m = new com.databridge.core.service.MigrationService(
                new com.databridge.core.port.memory.InMemoryRepositories.Clients(), new com.databridge.core.port.memory.InMemoryRepositories.Projects(),
                new com.databridge.core.port.memory.InMemoryRepositories.Runs(), audit, clock);
        com.databridge.core.port.memory.InMemoryRepositories.Checks checks = new com.databridge.core.port.memory.InMemoryRepositories.Checks();
        com.databridge.core.port.memory.InMemoryRepositories.Findings found = new com.databridge.core.port.memory.InMemoryRepositories.Findings();
        com.databridge.core.service.RuleService rules = new com.databridge.core.service.RuleService(
                new com.databridge.core.port.memory.InMemoryRepositories.Rules(), audit, clock);
        com.databridge.core.service.IntakeService intake = new com.databridge.core.service.IntakeService(m,
                new com.databridge.core.port.memory.InMemoryRepositories.Datasets(), setups, rules, checks, found,
                new com.databridge.core.workspace.WorkspaceManagers(), audit, clock);
        com.databridge.core.service.FindingService findings = new com.databridge.core.service.FindingService(checks, found, m, audit, clock);
        String client = m.createClient("CLI", "Command line", "cli").id();
        String project = m.createProject(client, "CLI", "Command line", null, "cli").id();
        String run = m.openRun(project, com.databridge.core.domain.RunType.MOCK, java.time.LocalDate.now(clock), "cli").id();

        Outcome<com.databridge.core.service.SubmissionResult> res = Outcome.run("validate", () -> {
            com.databridge.core.service.Submission.View sub = intake.openSubmission(run, "cli");
            for (String f : a.positional) {
                Path p = Path.of(f);
                if (!Files.isRegularFile(p)) throw new IllegalArgumentException("file not found: " + f);
                try (java.io.InputStream in = Files.newInputStream(p)) {
                    intake.addFile(sub.id(), kindOf(p.getFileName().toString()), p.getFileName().toString(), in, "cli");
                }
            }
            return intake.process(sub.id(), "cli");
        });
        if (!res.isSuccess()) {
            err.println("databridge validate: " + res.error().orElse(""));
            return STEP_ERROR;
        }
        com.databridge.core.domain.ValidationCheck c = res.value().check();
        out.printf("%s: %d row(s), %d critical, %d warning(s), %d info, readiness %.1f%%%n", c.status(), c.rows(), c.critical(),
                c.warning(), c.info(), c.readiness().doubleValue() * 100);
        for (com.databridge.core.validation.SkippedRule k : res.value().validation().skipped()) {
            out.println("  not checked: " + k.ruleId() + " (" + k.reason() + ")");
        }
        int shown = 0;
        for (com.databridge.core.finding.Finding f : res.value().validation().findings()) {
            if (shown++ == 50) {
                out.println("  ... " + (res.value().validation().findings().size() - 50) + " more; use --out to get the full register");
                break;
            }
            out.printf("  %-8s %s!%s%d %s: %s%n", f.severity(), f.sheet(), f.column(), f.row(), f.field(), f.message());
        }
        if (a.option("out") != null) {
            try {
                Path target = Path.of(a.option("out"));
                Files.write(target, findings.register(c.id()));
                out.println("Exception register written to " + target);
            } catch (java.io.IOException e) {
                err.println("databridge validate: " + e.getMessage());
                return STEP_ERROR;
            }
        }
        return c.status() == com.databridge.core.run.RunStatus.ERROR ? STEP_ERROR
                : c.status() == com.databridge.core.run.RunStatus.FAILED ? FINDINGS : OK;
    }

    static com.databridge.core.domain.TemplateKind kindOf(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("transaction")) return com.databridge.core.domain.TemplateKind.TRANSACTIONAL;
        if (n.contains("hr")) return com.databridge.core.domain.TemplateKind.HR;
        if (n.contains("static")) return com.databridge.core.domain.TemplateKind.STATIC;
        return com.databridge.core.domain.TemplateKind.OTHER;
    }

    private int purge(Args a, EffectiveSetup setup) {
        boolean all = a.flags.contains("all"), expired = a.flags.contains("expired");
        if (all == expired) {
            err.println("databridge purge: choose exactly one of --all or --expired");
            return USAGE;
        }
        DataBridgeSetup g = setup.general();
        WorkspaceManager workspaces = new WorkspaceManager(Path.of(g.resolvedWorkspaceDir()), g.getRetention());
        Outcome<PurgeReport> r = Outcome.run("purge", () -> all ? workspaces.purgeAll() : workspaces.purgeExpired());
        if (!r.isSuccess()) {
            err.println("databridge purge: " + r.error().orElse(""));
            return STEP_ERROR;
        }
        PurgeReport p = r.value();
        out.printf("Purged %d workspace(s): %d file(s), %d byte(s) from %s%n",
                p.workspaces(), p.files(), p.bytes(), workspaces.root());
        return OK;
    }

    private int showSetup(EffectiveSetup setup) {
        try {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("general", setup.general());
            view.put("resolvedWorkspaceDir", setup.general().resolvedWorkspaceDir());
            view.put("templateSetups", setup.templates().all());
            out.println(SetupLoader.mapper().writeValueAsString(view));
            return OK;
        } catch (Exception e) {
            err.println("databridge setup: " + e.getMessage());
            return STEP_ERROR;
        }
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 3) + "...";
    }

    static void usage(PrintStream p) {
        p.println("DataBridge core");
        p.println();
        p.println("Usage: databridge <command> [options]");
        p.println("  schema <workbook.xlsx>... [--out <registry.json>] [--setup <dir>]");
        p.println("        Build the schema registry from template workbooks.");
        p.println("  validate <workbook.xlsx>... [--out <register.xlsx>] [--setup <dir>]");
        p.println("        Validate a template set together (kind taken from each file name). Exit 3 on Critical findings.");
        p.println("  purge --expired | --all [--setup <dir>]");
        p.println("        Destroy expired workspaces, or every workspace (clears all client files).");
        p.println("  setup [--setup <dir>]");
        p.println("        Show the setups in force (defaults merged with <dir>).");
    }

    /** Minimal argument parser: positionals, --flag, --option value. */
    static final class Args {
        final String command;
        final List<String> positional = new ArrayList<>();
        final List<String> flags = new ArrayList<>();
        final Map<String, String> options = new LinkedHashMap<>();

        private Args(String command) { this.command = command; }

        static Args parse(String[] args) {
            Args a = new Args(args[0]);
            for (int i = 1; i < args.length; i++) {
                String s = args[i];
                if (s.equals("--out") || s.equals("--setup")) {
                    if (i + 1 >= args.length) throw new IllegalArgumentException(s + " needs a value");
                    a.options.put(s.substring(2), args[++i]);
                } else if (s.equals("--all") || s.equals("--expired")) {
                    a.flags.add(s.substring(2));
                } else if (s.startsWith("--")) {
                    throw new IllegalArgumentException("unknown option " + s);
                } else {
                    a.positional.add(s);
                }
            }
            return a;
        }

        String option(String name) { return options.get(name); }
    }
}
