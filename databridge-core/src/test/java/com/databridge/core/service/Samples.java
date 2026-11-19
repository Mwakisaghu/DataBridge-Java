package com.databridge.core.service;

import java.nio.file.Files;
import java.nio.file.Path;

/** Finds the synthetic sample data set (samples/databridge-sample-data) from any module directory. */
public final class Samples {
    private Samples() { }

    public static Path dir() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            Path s = p.resolve("samples/databridge-sample-data");
            if (Files.isDirectory(s)) return s;
        }
        throw new IllegalStateException("samples/databridge-sample-data not found above " + Path.of("").toAbsolutePath());
    }
}
