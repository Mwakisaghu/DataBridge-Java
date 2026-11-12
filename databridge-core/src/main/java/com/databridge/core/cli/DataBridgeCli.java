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
 *   purge  --expired | --all [--setup &lt;dir&gt;]
 *   setup  [--setup &lt;dir&gt;]
 * </pre>
 * Exit codes: 0 success, 1 usage or setup error, 2 a step ended in ERROR.
 */
public final class DataBridgeCli {

    static final int OK = 0, USAGE = 1, STEP_ERROR = 2;

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
