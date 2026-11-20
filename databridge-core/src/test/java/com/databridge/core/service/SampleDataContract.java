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
        return submit(fx, run, set, false);
    }

    /** Submits the three workbooks of a set, cleaning each first when {@code cleaned}. */
    private List<StoredFinding> submit(Fixture fx, String run, String set, boolean cleaned) throws Exception {
        Submission.View s = fx.intake.openSubmission(run, "harry");
        for (String[] f : FILES) {
            Path p = Samples.dir().resolve(set).resolve("Sample " + f[0] + " Data Templates.xlsx");
            TemplateKind kind = TemplateKind.valueOf(f[1]);
            if (cleaned) {
                CleaningService.Output out;
                try (InputStream in = Files.newInputStream(p)) {
                    out = fx.cleaning.clean(run, kind, p.getFileName().toString(), in, "harry");
                }
                fx.intake.addFile(s.id(), kind, out.fileName(), new java.io.ByteArrayInputStream(out.workbook()), "harry");
                continue;
            }
            try (InputStream in = Files.newInputStream(p)) {
                fx.intake.addFile(s.id(), kind, p.getFileName().toString(), in, "harry");
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
    void cleaningFixesExactlyTheAutoFixablePlantedErrorsAndNothingElse() throws Exception {
        Fixture fx = fixture(tmp);
        Client c = fx.migrations.createClient("CLEAN", "CLEAN SACCO", "harry");
        Project p = fx.migrations.createProject(c.id(), "TEST", "Cleaning run", null, "harry");
        MigrationRun run = fx.migrations.openRun(p.id(), RunType.MOCK, LocalDate.of(2026, 10, 31), "harry");

        Set<String> before = keys(submit(fx, run.id(), "with-errors"));
        Set<String> after = keys(submit(fx, run.id(), "with-errors", true));
        Set<String> fixed = new java.util.TreeSet<>(before);
        fixed.removeAll(after);
        Set<String> added = new java.util.TreeSet<>(after);
        added.removeAll(before);

        Set<String> autoFixable = new java.util.TreeSet<>();
        for (JsonNode s : new ObjectMapper().readTree(Samples.dir().resolve("seeded-errors.json").toFile())) {
            if (s.get("auto_fixable").asText().equals("Yes")) {
                autoFixable.add(key(s.get("sheet").asText(), s.get("row").asInt(), s.get("rule").asText()));
            }
        }
        assertEquals(5, autoFixable.size());
        assertEquals(autoFixable, fixed, "every auto-fixable planted error, and only those, is fixed");
        assertTrue(added.isEmpty(), () -> "cleaning must not create new findings: " + added);

        List<com.databridge.core.domain.CleaningJob> jobs = fx.cleaning.jobs(run.id());
        assertEquals(3, jobs.size());
        assertTrue(jobs.stream().allMatch(j -> j.sha256Out() != null && !j.sha256Out().equals(j.sha256In())));
        var log = fx.cleaning.job(jobs.stream().filter(j -> j.kind() == TemplateKind.STATIC).findFirst().orElseThrow().id()).changes();
        assertTrue(log.stream().anyMatch(ch -> ch.sheet().equals("Savings Accounts") && ch.rows().equals("571")));
        assertTrue(log.stream().anyMatch(ch -> ch.field().equals("Phone No.") && ch.rows().contains("43")));
        try (Stream<Path> left = Files.exists(fx.workspaceRoot) ? Files.list(fx.workspaceRoot) : Stream.empty()) {
            assertEquals(0, left.count(), "no client files left after cleaning");
        }
    }

    private static Set<String> keys(List<StoredFinding> fs) {
        return fs.stream().map(f -> key(f.sheet(), f.rowNo(), f.ruleType())).collect(Collectors.toSet());
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
