package com.databridge.core.cleaning;

import com.databridge.core.setup.SetupException;
import com.databridge.core.setup.SetupLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Loads the shipped default Cleaning Setup, {@code /setup/cleaning/default.json}. */
public final class CleanLoader {

    private CleanLoader() { }

    public static List<CleanRule> defaults() {
        try (InputStream in = CleanLoader.class.getResourceAsStream("/setup/cleaning/default.json")) {
            if (in == null) return List.of();
            return List.of(SetupLoader.mapper().readValue(in, CleanRule[].class));
        } catch (IOException e) {
            throw new SetupException("Cannot read the default Cleaning Setup: " + e.getMessage(), e);
        }
    }
}
