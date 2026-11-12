package com.databridge.core.run;

import com.databridge.core.schema.SchemaExtractor;
import com.databridge.core.schema.WorkbookSchema;
import com.databridge.core.setup.SetupLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fail closed: a step that crashes must never be reported as a pass. */
class OutcomeTest {

    @TempDir Path tmp;

    @Test
    void exceptionBecomesErrorNotSuccess() {
        Outcome<String> o = Outcome.run("validate", () -> { throw new IllegalStateException("boom"); });
        assertEquals(RunStatus.ERROR, o.status());
        assertFalse(o.isSuccess());
        assertTrue(o.error().orElseThrow().contains("boom"));
    }

    @Test
    void linkageErrorAlsoFailsClosed() {
        // The legacy validators reported "success" when POI classes were missing.
        Outcome<String> o = Outcome.run("validate", () -> { throw new NoClassDefFoundError("TypeSystemHolder"); });
        assertEquals(RunStatus.ERROR, o.status());
    }

    @Test
    void errorHasNoValue() {
        Outcome<String> o = Outcome.run("validate", () -> { throw new RuntimeException(); });
        assertThrows(IllegalStateException.class, o::value);
    }

    @Test
    void failedIsNotSuccessButKeepsItsValue() {
        Outcome<Integer> o = Outcome.failed("validate", 12, "12 critical findings");
        assertFalse(o.isSuccess());
        assertEquals(12, o.value());
    }

    @Test
    void successCarriesValue() {
        Outcome<Integer> o = Outcome.run("count", () -> 41);
        assertTrue(o.isSuccess());
        assertEquals(41, o.value());
    }

    @Test
    void corruptWorkbookFailsClosed() throws Exception {
        Path bad = tmp.resolve("corrupt.xlsx");
        Files.write(bad, new byte[] {1, 2, 3, 4, 5});
        SchemaExtractor x = new SchemaExtractor(SetupLoader.loadDefaults().templates(), 10);
        Outcome<WorkbookSchema> o = Outcome.run("schema", () -> x.extract(bad));
        assertEquals(RunStatus.ERROR, o.status());
    }
}
