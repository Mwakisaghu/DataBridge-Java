package com.databridge.core.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupLoaderTest {

    @TempDir Path dir;

    @Test
    void defaultsAreSafe() {
        EffectiveSetup s = SetupLoader.loadDefaults();
        assertEquals(RetentionMode.PURGE_ON_COMPLETE, s.general().getRetention().getMode());
        assertTrue(s.general().getRetention().isSecureOverwrite());
        assertEquals(FindingValueMode.MASKED, s.general().getFindingValueMode());
    }

    @Test
    void defaultTemplateSetupsAreLoaded() {
        TemplateSetup t = SetupLoader.loadDefaults().templates();
        assertTrue(t.find("TRIAL BALANCE").isPresent());
        assertTrue(t.find("  trial   balance ").isPresent(), "matching ignores case and spacing");
        assertEquals(15, t.find("Chart of Account").orElseThrow().getTableId());
    }

    @Test
    void installationSetupOverridesOnlyWhatItNames() throws Exception {
        Files.writeString(dir.resolve("databridge-setup.json"),
                "{\"retention\":{\"mode\":\"KEEP_FOR_HOURS\",\"keepHours\":48}}");
        DataBridgeSetup g = SetupLoader.load(dir).general();
        assertEquals(RetentionMode.KEEP_FOR_HOURS, g.getRetention().getMode());
        assertEquals(48, g.getRetention().getKeepHours());
        assertTrue(g.getRetention().isSecureOverwrite(), "unnamed values keep their defaults");
        assertEquals(FindingValueMode.MASKED, g.getFindingValueMode());
    }

    @Test
    void installationTemplateSetupReplacesDefaultForThatSheet() throws Exception {
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/client-a.json"),
                "[{\"sheetName\":\"Trial Balance\",\"captionRow\":2}]");
        SheetSetup tb = SetupLoader.load(dir).templates().find("TRIAL BALANCE").orElseThrow();
        assertEquals(2, tb.getCaptionRow());
    }

    @Test
    void invalidValueIsRejected() throws Exception {
        Files.writeString(dir.resolve("databridge-setup.json"), "{\"retention\":{\"keepHours\":0}}");
        assertThrows(SetupException.class, () -> SetupLoader.load(dir));
    }

    @Test
    void misspeltSettingIsRejectedNotIgnored() throws Exception {
        Files.writeString(dir.resolve("databridge-setup.json"), "{\"retension\":{\"mode\":\"KEEP_FOR_HOURS\"}}");
        assertThrows(SetupException.class, () -> SetupLoader.load(dir));
    }

    @Test
    void missingSetupFolderIsRejected() {
        assertThrows(SetupException.class, () -> SetupLoader.load(dir.resolve("nope")));
    }
}
