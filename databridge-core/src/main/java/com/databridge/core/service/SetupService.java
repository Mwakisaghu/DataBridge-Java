package com.databridge.core.service;

import com.databridge.core.port.SetupRepository;
import com.databridge.core.schema.FieldTypeParser;
import com.databridge.core.setup.DataBridgeSetup;
import com.databridge.core.setup.EffectiveSetup;
import com.databridge.core.setup.FieldSetup;
import com.databridge.core.setup.SetupException;
import com.databridge.core.setup.SetupLoader;
import com.databridge.core.setup.SheetSetup;
import com.databridge.core.setup.TemplateSetup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads and changes setups. Effective setup = shipped defaults, overridden by what
 * is stored: a stored DataBridge Setup replaces the default one, and stored Template
 * Setups replace the shipped ones sheet by sheet. Every change is validated first and
 * audited with what changed.
 */
public final class SetupService {

    static final String ENTITY = "SETUP";

    private final SetupRepository repo;
    private final AuditLog audit;
    private final Clock clock;
    private final EffectiveSetup defaults;
    private final ObjectMapper mapper = SetupLoader.mapper();

    public SetupService(SetupRepository repo, AuditLog audit, Clock clock) {
        this(repo, audit, clock, SetupLoader.loadDefaults());
    }

    public SetupService(SetupRepository repo, AuditLog audit, Clock clock, EffectiveSetup defaults) {
        this.repo = repo;
        this.audit = audit;
        this.clock = clock;
        this.defaults = defaults;
    }

    public EffectiveSetup effective() {
        DataBridgeSetup general = copy(repo.loadGeneral().orElse(defaults.general()));
        TemplateSetup templates = new TemplateSetup();
        defaults.templates().all().forEach(templates::put);
        repo.loadSheets().forEach(templates::put);
        return new EffectiveSetup(general, templates);
    }

    /** Stored overrides only (what an export carries). */
    public SetupBundle stored() {
        return new SetupBundle(repo.loadGeneral().map(this::copy).orElse(null), repo.loadSheets());
    }

    public DataBridgeSetup updateGeneral(DataBridgeSetup updated, String actor) {
        String who = AuditLog.actor(actor);
        try {
            updated.validate();
        } catch (SetupException e) {
            throw new ValidationException(e.getMessage());
        }
        DataBridgeSetup before = effective().general();
        repo.saveGeneral(copy(updated), who, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        String diff = diff(mapper.valueToTree(before), mapper.valueToTree(updated));
        audit.record(who, "SETUP_CHANGED", ENTITY, "DataBridge Setup", diff.isEmpty() ? "No values changed" : diff);
        return updated;
    }

    public SheetSetup putSheet(SheetSetup sheet, String actor) {
        String who = AuditLog.actor(actor);
        validateSheet(sheet);
        boolean existed = repo.loadSheets().stream()
                .anyMatch(s -> TemplateSetup.normalise(s.getSheetName()).equals(TemplateSetup.normalise(sheet.getSheetName())));
        repo.saveSheet(sheet, who, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        audit.record(who, existed ? "TEMPLATE_SETUP_CHANGED" : "TEMPLATE_SETUP_ADDED", ENTITY, sheet.getSheetName(),
                describe(sheet));
        return sheet;
    }

    public boolean deleteSheet(String sheetName, String actor) {
        String who = AuditLog.actor(actor);
        boolean removed = repo.deleteSheet(sheetName);
        if (removed) audit.record(who, "TEMPLATE_SETUP_DELETED", ENTITY, sheetName, "Shipped default applies again, if any");
        return removed;
    }

    /** Validates every part first, then saves; nothing is saved if any part is invalid. */
    public void importBundle(SetupBundle bundle, String actor) {
        String who = AuditLog.actor(actor);
        if (bundle.general() != null) {
            try {
                bundle.general().validate();
            } catch (SetupException e) {
                throw new ValidationException("DataBridge Setup: " + e.getMessage());
            }
        }
        bundle.templates().forEach(SetupService::validateSheet);
        if (bundle.general() != null) updateGeneral(bundle.general(), who);
        for (SheetSetup s : bundle.templates()) putSheet(s, who);
        audit.record(who, "SETUP_IMPORTED", ENTITY, null,
                (bundle.general() != null ? "DataBridge Setup and " : "") + bundle.templates().size() + " Template Setup(s)");
    }

    static void validateSheet(SheetSetup s) {
        if (s.getSheetName() == null || s.getSheetName().isBlank()) throw new ValidationException("sheetName is required");
        String name = s.getSheetName();
        if (name.length() > 250) throw new ValidationException("sheetName is longer than 250 characters");
        if (s.getCaptionRow() != null && (s.getCaptionRow() < 1 || s.getCaptionRow() > 50)) {
            throw new ValidationException(name + ": captionRow must be 1-50");
        }
        if (s.getTableId() != null && s.getTableId() < 1) throw new ValidationException(name + ": tableId must be positive");
        Set<String> seen = new HashSet<>();
        for (FieldSetup f : s.getFields()) {
            if (f.getCaption() == null || f.getCaption().isBlank()) throw new ValidationException(name + ": every field needs a caption");
            if (!seen.add(TemplateSetup.normalise(f.getCaption()))) {
                throw new ValidationException(name + ": caption '" + f.getCaption() + "' is listed twice");
            }
            if (f.getType() == null || !FieldTypeParser.parse(f.getType()).isKnown()) {
                throw new ValidationException(name + ": field '" + f.getCaption() + "' has an unknown type '" + f.getType()
                        + "' (use Code20, Text100, Decimal, Integer, Boolean, Date, DateFormula or Option)");
            }
        }
    }

    private static String describe(SheetSetup s) {
        List<String> parts = new ArrayList<>();
        if (s.getTableId() != null) parts.add("table " + s.getTableId());
        if (s.getCaptionRow() != null) parts.add("caption row " + s.getCaptionRow());
        parts.add(s.getFields().size() + " field(s)");
        return String.join(", ", parts);
    }

    /** Setups are mutable; callers always get, and the store always keeps, a private copy. */
    private DataBridgeSetup copy(DataBridgeSetup s) {
        return mapper.convertValue(mapper.valueToTree(s), DataBridgeSetup.class);
    }

    /** "retention.mode: PURGE_ON_COMPLETE -> KEEP_FOR_HOURS; maxUploadMb: 200 -> 300" */
    static String diff(JsonNode before, JsonNode after) {
        List<String> changes = new ArrayList<>();
        diff("", before, after, changes);
        return String.join("; ", changes);
    }

    private static void diff(String path, JsonNode a, JsonNode b, List<String> out) {
        if (a != null && b != null && a.isObject() && b.isObject()) {
            Set<String> keys = new java.util.TreeSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) diff(path.isEmpty() ? k : path + "." + k, a.get(k), b.get(k), out);
        } else if (a == null ? b != null : !a.equals(b)) {
            out.add(path + ": " + text(a) + " -> " + text(b));
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? "(none)" : n.isTextual() ? n.asText() : n.toString();
    }

}
