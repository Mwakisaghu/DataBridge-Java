package com.databridge.core.validation;

import com.databridge.core.setup.SetupException;
import com.databridge.core.setup.SetupLoader;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Loads the shipped default Rule Setup from {@code /setup/rules/index.txt}. */
public final class RuleLoader {

    private RuleLoader() { }

    public static List<RuleDef> defaults() {
        ObjectMapper m = SetupLoader.mapper();
        List<RuleDef> out = new ArrayList<>();
        for (String name : index()) {
            try (InputStream in = RuleLoader.class.getResourceAsStream("/setup/rules/" + name)) {
                if (in == null) throw new SetupException("Missing default rules /setup/rules/" + name);
                for (RuleDef r : m.readValue(in, RuleDef[].class)) out.add(r);
            } catch (IOException e) {
                throw new SetupException("Cannot read default rules " + name + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    private static List<String> index() {
        try (InputStream in = RuleLoader.class.getResourceAsStream("/setup/rules/index.txt")) {
            if (in == null) return List.of();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return r.lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
            }
        } catch (IOException e) {
            throw new SetupException("Cannot read /setup/rules/index.txt", e);
        }
    }
}
