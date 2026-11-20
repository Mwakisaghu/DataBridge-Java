package com.databridge.core.integration;

import com.databridge.core.setup.TemplateSetup;
import com.databridge.core.validation.RuleDef;
import com.databridge.core.validation.RuleType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order in which sheets must be imported: a sheet comes after every sheet it references
 * (from the Rule Setup's REFERENCE rules), otherwise in the order given. A cycle is broken in the
 * given order and reported.
 */
public final class ImportOrder {

    /** The ordered sheets and any reference cycles that had to be broken. */
    public record Result(List<String> sheets, List<String> notes) { }

    private ImportOrder() { }

    public static Result of(List<String> sheets, List<RuleDef> rules) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String s : sheets) byKey.putIfAbsent(TemplateSetup.normalise(s), s);
        Map<String, Set<String>> needs = new LinkedHashMap<>();
        for (String k : byKey.keySet()) needs.put(k, new LinkedHashSet<>());
        for (RuleDef r : rules) {
            if (!r.isEnabled() || r.getType() != RuleType.REFERENCE || r.getTargetSheet() == null) continue;
            String from = TemplateSetup.normalise(r.getSheet());
            String to = TemplateSetup.normalise(r.getTargetSheet());
            if (!from.equals(to) && needs.containsKey(from) && needs.containsKey(to)) needs.get(from).add(to);
        }
        List<String> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        while (done.size() < needs.size()) {
            String next = null;
            for (Map.Entry<String, Set<String>> e : needs.entrySet()) {
                if (!done.contains(e.getKey()) && done.containsAll(e.getValue())) {
                    next = e.getKey();
                    break;
                }
            }
            if (next == null) { // cycle: take the first remaining sheet in the given order
                for (String k : needs.keySet()) {
                    if (!done.contains(k)) {
                        next = k;
                        notes.add("References between '" + byKey.get(k) + "' and other sheets form a cycle; import it in the given order");
                        break;
                    }
                }
            }
            done.add(next);
            out.add(byKey.get(next));
        }
        return new Result(out, notes);
    }
}
