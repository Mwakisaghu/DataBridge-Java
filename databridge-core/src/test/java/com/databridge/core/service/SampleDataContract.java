package com.databridge.core.service;

import com.databridge.core.domain.Client;
import com.databridge.core.domain.FindingFilter;
import com.databridge.core.domain.MigrationRun;
import com.databridge.core.domain.Project;
import com.databridge.core.domain.RunType;
import com.databridge.core.domain.StoredFinding;
import com.databridge.core.domain.TemplateKind;
import com.databridge.core.run.RunStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden test: the synthetic sample set, submitted as a whole, must give exactly the answer key.
 * The clean set has only the genuine template flaw (12-digit phone numbers in two HR columns typed
 * Integer); the error set adds exactly the planted errors, with the expected severities.
 */
public abstract class SampleDataContract {

    @TempDir protected Path tmp;

    protected abstract Fixture fixture(Path tmp);

    static final String[][] FILES = {{"Static", "STATIC"}, {"HR", "HR"}, {"Transactional", "TRANSACTIONAL"}};

    private List<StoredFinding> submit(Fixture fx, String run, String set) throws Exception {
        Submission.View s = fx.intake.openSubmission(run, "harry");
        for (String[] f : FILES) {
            Path p = Samples.dir().resolve(set).resolve("Sample " + f[0] + " Data Templates.xlsx");
            try (InputStream in = Files.newInputStream(p)) {
                fx.intake.addFile(s.id(), TemplateKind.valueOf(f[1]), p.getFileName().toString(), in, "harry");
            }
        }
        SubmissionResult r = fx.intake.process(s.id(), "harry");
        assertEquals(3, r.datasets().size());
        assertTrue(r.datasets().stream().allMatch(IntakeResult::succeeded));
        assertEquals(RunStatus.FAILED, r.check().status(), "Critical findings mean the check fails, never errors");
        assertTrue(r.validation().skipped().isEmpty(), () -> "all rules run with the full set: " + r.validation().skipped());
        return fx.findings.page(r.check().id(), FindingFilter.all(), 0, 10_000).items();
    }

    private static String key(String sheet, int row, String type) {
        return sheet + "|" + row + "|" + type;
    }

    @Test
    void sampleSetGivesExactlyTheAnswerKey() throws Exception {
        Fixture fx = fixture(tmp);
        Client c = fx.migrations.createClient("SAMPLE", "SAMPLE SACCO", "harry");
        Project p = fx.migrations.createProject(c.id(), "TEST", "Sample run", null, "harry");
        MigrationRun run = fx.migrations.openRun(p.id(), RunType.MOCK, LocalDate.of(2026, 10, 31), "harry");

        List<StoredFinding> clean = submit(fx, run.id(), "clean");
        assertEquals(48, clean.size(), "the clean set reconciles: no TB or control findings");
        assertTrue(clean.stream().allMatch(f -> f.sheet().equals("Employee Upload") && f.ruleType().equals("NUMBER")
                && f.message().contains("too large") && (f.field().contains("Phone Number"))), "only the template's Integer phone columns");

        List<StoredFinding> withErrors = submit(fx, run.id(), "with-errors");
        Set<String> cleanKeys = clean.stream().map(f -> key(f.sheet(), f.rowNo(), f.ruleType())).collect(Collectors.toSet());
        Map<String, StoredFinding> extra = new HashMap<>();
        for (StoredFinding f : withErrors) {
            String k = key(f.sheet(), f.rowNo(), f.ruleType());
            if (!cleanKeys.contains(k)) extra.put(k, f);
        }

        JsonNode seeded = new ObjectMapper().readTree(Samples.dir().resolve("seeded-errors.json").toFile());
        Map<String, String> expected = new HashMap<>();
        for (JsonNode s : seeded) {
            String type = s.get("rule").asText();
            expected.put(key(s.get("sheet").asText(), s.get("row").asInt(), type), s.get("severity").asText().toUpperCase());
        }
        assertEquals(expected.keySet(), extra.keySet());
        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertEquals(e.getValue(), extra.get(e.getKey()).severity().name(), e.getKey());
        }

        // The reconciliation lines behind those findings: the TB and nine control accounts.
        var lines = fx.recon.lines(fx.findings.checks(run.id()).get(0).id());
        assertEquals(10, lines.size());
        Map<String, String> variances = new HashMap<>();
        for (var l : lines) {
            if (l.line().variance() != null && l.line().variance().signum() != 0) {
                variances.put(l.line().type() == com.databridge.core.recon.ReconLine.Type.TB ? "TB" : l.line().glAccount(),
                        l.line().variance().toPlainString());
            }
        }
        assertEquals(Map.of("TB", "1000.00", "102110", "81000.00", "201210", "50000.00"), variances);
        assertTrue(extra.get(key("TRIAL BALANCE", 2, "CONTROL")).message().contains("81,000.00 below"));
        assertTrue(extra.get(key("TRIAL BALANCE", 33, "TB_BALANCE")).message().equals("Debits exceed credits by 1,000.00"));

        try (Stream<Path> left = Files.exists(fx.workspaceRoot) ? Files.list(fx.workspaceRoot) : Stream.empty()) {
            assertEquals(0, left.count(), "no client files left after processing");
        }
    }
}
