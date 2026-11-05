package com.databridge.core.setup;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads setups in two layers:
 * <ol>
 *   <li>shipped defaults on the classpath under {@code /setup/};</li>
 *   <li>an optional installation setup folder holding {@code databridge-setup.json}
 *       and {@code templates/*.json}, which override the defaults.</li>
 * </ol>
 */
public final class SetupLoader {

    private static final String DEFAULT_GENERAL = "/setup/databridge-setup.json";
    private static final String DEFAULT_TEMPLATE_INDEX = "/setup/templates/index.txt";

    private SetupLoader() { }

    public static ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        m.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        m.setDefaultMergeable(true);
        m.enable(SerializationFeature.INDENT_OUTPUT);
        return m;
    }

    /** Defaults only. */
    public static EffectiveSetup loadDefaults() {
        return load(null);
    }

    /** Defaults merged with the files in {@code setupDir} (may be null). */
    public static EffectiveSetup load(Path setupDir) {
        ObjectMapper m = mapper();
        DataBridgeSetup general = readClasspath(m, DEFAULT_GENERAL, DataBridgeSetup.class);
        TemplateSetup templates = new TemplateSetup();
        for (String name : templateIndex()) {
            for (SheetSetup s : readClasspath(m, "/setup/templates/" + name, SheetSetup[].class)) {
                templates.put(s);
            }
        }

        if (setupDir != null) {
            if (!Files.isDirectory(setupDir)) {
                throw new SetupException("Setup folder not found: " + setupDir);
            }
            Path generalFile = setupDir.resolve("databridge-setup.json");
            if (Files.isRegularFile(generalFile)) {
                try {
                    m.readerForUpdating(general).readValue(generalFile.toFile());
                } catch (IOException e) {
                    throw new SetupException("Cannot read " + generalFile + ": " + e.getMessage(), e);
                }
            }
            Path templateDir = setupDir.resolve("templates");
            if (Files.isDirectory(templateDir)) {
                try (Stream<Path> files = Files.list(templateDir)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                        for (SheetSetup s : m.readValue(f.toFile(), SheetSetup[].class)) {
                            templates.put(s);
                        }
                    }
                } catch (IOException e) {
                    throw new SetupException("Cannot read template setups in " + templateDir + ": " + e.getMessage(), e);
                }
            }
        }
        general.validate();
        return new EffectiveSetup(general, templates);
    }

    private static List<String> templateIndex() {
        try (InputStream in = SetupLoader.class.getResourceAsStream(DEFAULT_TEMPLATE_INDEX)) {
            if (in == null) return List.of();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return r.lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
            }
        } catch (IOException e) {
            throw new SetupException("Cannot read " + DEFAULT_TEMPLATE_INDEX, e);
        }
    }

    private static <T> T readClasspath(ObjectMapper m, String resource, Class<T> type) {
        try (InputStream in = SetupLoader.class.getResourceAsStream(resource)) {
            if (in == null) throw new SetupException("Missing default setup " + resource);
            return m.readValue(in, type);
        } catch (IOException e) {
            throw new SetupException("Cannot read default setup " + resource + ": " + e.getMessage(), e);
        }
    }
}
