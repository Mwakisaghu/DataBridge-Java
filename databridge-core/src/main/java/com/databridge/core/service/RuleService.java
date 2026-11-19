package com.databridge.core.service;

import com.databridge.core.port.RuleRepository;
import com.databridge.core.validation.Patterns;
import com.databridge.core.validation.RuleDef;
import com.databridge.core.validation.RuleLoader;
import com.databridge.core.validation.RuleType;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The Rule Setup: shipped default rules, overridden or added to by stored rules with the same id. */
public final class RuleService {

    /** A rule in force, and whether it comes from the shipped defaults, is stored here, or both. */
    public record RuleView(RuleDef rule, boolean shipped, boolean stored) { }

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9.\\-_]{1,79}$");
    private static final Set<String> OPERATORS = Set.of("LT", "LE", "EQ", "GE", "GT");

    private final RuleRepository repo;
    private final AuditLog audit;
    private final Clock clock;
    private final List<RuleDef> defaults;

    public RuleService(RuleRepository repo, AuditLog audit, Clock clock) {
        this(repo, audit, clock, RuleLoader.defaults());
    }

    public RuleService(RuleRepository repo, AuditLog audit, Clock clock, List<RuleDef> defaults) {
        this.repo = repo;
        this.audit = audit;
        this.clock = clock;
        this.defaults = List.copyOf(defaults);
    }

    /** Rules in force, enabled or not. */
    public List<RuleDef> effective() {
        return views().stream().map(RuleView::rule).toList();
    }

    public List<RuleView> views() {
        Map<String, RuleView> out = new LinkedHashMap<>();
        for (RuleDef d : defaults) out.put(d.getId(), new RuleView(copy(d), true, false));
        for (RuleDef s : repo.list()) out.put(s.getId(), new RuleView(copy(s), out.containsKey(s.getId()), true));
        return new ArrayList<>(out.values());
    }

    public RuleDef put(RuleDef rule, String actor) {
        String who = AuditLog.actor(actor);
        validate(rule);
        boolean existed = views().stream().anyMatch(v -> v.rule().getId().equals(rule.getId()));
        repo.save(rule, who, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        audit.record(who, existed ? "RULE_CHANGED" : "RULE_ADDED", "RULE", rule.getId(),
                rule.getType() + " on " + rule.getSheet() + " / " + rule.getField() + ", " + rule.getSeverity()
                        + (rule.isEnabled() ? "" : ", switched off"));
        return rule;
    }

    public boolean delete(String id, String actor) {
        String who = AuditLog.actor(actor);
        boolean removed = repo.delete(id);
        if (removed) audit.record(who, "RULE_DELETED", "RULE", id, "Shipped default applies again, if any");
        return removed;
    }

    /** Rules are mutable; callers always get a private copy, so the shipped defaults cannot be changed by accident. */
    private static RuleDef copy(RuleDef r) {
        com.fasterxml.jackson.databind.ObjectMapper m = com.databridge.core.setup.SetupLoader.mapper();
        return m.convertValue(m.valueToTree(r), RuleDef.class);
    }

    static void validate(RuleDef r) {
        if (r.getId() == null || !ID.matcher(r.getId()).matches()) {
            throw new ValidationException("Rule id must be 2-80 lower-case letters, digits, '.', '-' or '_'");
        }
        if (r.getType() == null) throw new ValidationException(r.getId() + ": type is required");
        if (blank(r.getSheet()) || blank(r.getField())) throw new ValidationException(r.getId() + ": sheet and field are required");
        if (r.getSeverity() == null) throw new ValidationException(r.getId() + ": severity is required");
        switch (r.getType()) {
            case FORMAT:
                if (blank(r.getPattern())) throw new ValidationException(r.getId() + ": a pattern is required");
                try {
                    Patterns.compile(r.getPattern());
                } catch (IllegalArgumentException e) {
                    throw new ValidationException(r.getId() + ": " + e.getMessage());
                }
                break;
            case REFERENCE:
                if (blank(r.getTargetSheet()) || blank(r.getTargetField())) {
                    throw new ValidationException(r.getId() + ": targetSheet and targetField are required");
                }
                break;
            case SUM_EQUALS:
                if (blank(r.getGroupBy()) || r.getExpected() == null) {
                    throw new ValidationException(r.getId() + ": groupBy and expected are required");
                }
                break;
            case COMPARE:
                if (blank(r.getOtherField()) || r.getOperator() == null
                        || !OPERATORS.contains(r.getOperator().toUpperCase(Locale.ROOT))) {
                    throw new ValidationException(r.getId() + ": otherField and an operator (LT, LE, EQ, GE, GT) are required");
                }
                break;
            default:
                break;
        }
        if (r.getType() == RuleType.REQUIRED && r.isAutoFixable()) {
            throw new ValidationException(r.getId() + ": a missing value cannot be fixed automatically");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
