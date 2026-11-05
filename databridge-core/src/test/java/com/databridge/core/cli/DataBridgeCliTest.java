package com.databridge.core.cli;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static com.databridge.core.support.TestWorkbooks.caption;
import static com.databridge.core.support.TestWorkbooks.save;
import static com.databridge.core.support.TestWorkbooks.set;
import static com.databridge.core.support.TestWorkbooks.workbook;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataBridgeCliTest {

    @TempDir Path tmp;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return new DataBridgeCli(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
    }

    private Path setupDir() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("setup"));
        String ws = tmp.resolve("workspaces").toString().replace("\\", "/");
        Files.writeString(dir.resolve("databridge-setup.json"), "{\"workspaceDir\":\"" + ws + "\"}");
        return dir;
    }

    private Path template() throws Exception {
        XSSFWorkbook wb = workbook();
        Sheet s = wb.createSheet("Members");
        set(s, 0, 0, "STATIC DATA");
        set(s, 0, 1, "Members");
        set(s, 0, 2, 52185700);
        caption(s, 2, 0, "No.", "Code50");
        caption(s, 2, 1, "Name", "Text80");
        set(s, 3, 0, "152009");
        set(s, 3, 1, "JOSEPH DOE DOE");
        return save(wb, tmp.resolve("Static_Data_Templates.xlsx"));
    }

    @Test
    void schemaBuildsRegistryAndLeavesNoClientFilesBehind() throws Exception {
        Path setup = setupDir();
        Path registry = tmp.resolve("out/registry.json");
        int code = run("schema", template().toString(), "--out", registry.toString(), "--setup", setup.toString());

        assertEquals(0, code, err.toString());
        assertTrue(out.toString().contains("52185700"));
        String json = Files.readString(registry);
        assertTrue(json.contains("\"caption\" : \"Name\""));
        assertFalse(json.contains("JOSEPH"), "registry must hold structure only, never data");
        try (Stream<Path> left = Files.list(tmp.resolve("workspaces"))) {
            assertEquals(0, left.count(), "workspace must be purged after the run");
        }
    }

    @Test
    void missingFileEndsInErrorExitCode() throws Exception {
        int code = run("schema", tmp.resolve("nope.xlsx").toString(), "--setup", setupDir().toString());
        assertEquals(DataBridgeCli.STEP_ERROR, code);
        assertTrue(out.toString().contains("ERROR"));
    }

    @Test
    void purgeNeedsExactlyOneMode() throws Exception {
        assertEquals(DataBridgeCli.USAGE, run("purge", "--setup", setupDir().toString()));
    }

    @Test
    void purgeAllReportsCounts() throws Exception {
        assertEquals(0, run("purge", "--all", "--setup", setupDir().toString()));
        assertTrue(out.toString().startsWith("Purged 0 workspace(s)"));
    }

    @Test
    void unknownOptionIsUsageError() {
        assertEquals(DataBridgeCli.USAGE, run("schema", "--colour", "red"));
    }
}
