package com.databridge.core.recon;

import com.databridge.core.setup.SetupException;
import com.databridge.core.setup.SetupLoader;

import java.io.IOException;
import java.io.InputStream;

/** Loads the shipped default Reconciliation Setup, {@code /setup/reconciliation/default.json}. */
public final class ReconLoader {

    private ReconLoader() { }

    public static ReconSetup defaults() {
        try (InputStream in = ReconLoader.class.getResourceAsStream("/setup/reconciliation/default.json")) {
            if (in == null) return new ReconSetup();
            return SetupLoader.mapper().readValue(in, ReconSetup.class);
        } catch (IOException e) {
            throw new SetupException("Cannot read the default Reconciliation Setup: " + e.getMessage(), e);
        }
    }
}
