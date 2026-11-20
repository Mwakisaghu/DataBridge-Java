package com.databridge.core.port;

import com.databridge.core.domain.ImportRecord;
import com.databridge.core.domain.PackageJob;
import com.databridge.core.integration.IntegrationSetup;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The stored Integration Setup, built packages and import tracking. */
public interface IntegrationRepository {
    Optional<IntegrationSetup> setup();
    void saveSetup(IntegrationSetup s, String actor, Instant at);

    void insertPackage(PackageJob job);
    List<PackageJob> packages(String runId);

    void upsertImport(ImportRecord r);
    List<ImportRecord> imports(String runId);
}
