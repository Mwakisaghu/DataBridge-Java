package com.databridge.store.jdbc;

import com.databridge.core.service.Fixture;
import com.databridge.core.service.SampleDataContract;

import java.nio.file.Path;

/** The golden sample-data test against the database schema. */
class JdbcSampleDataTest extends SampleDataContract {
    @Override
    protected Fixture fixture(Path tmp) {
        javax.sql.DataSource ds = TestDatabase.create();
        JdbcRepositories r = new JdbcRepositories(ds);
        JdbcValidationRepositories v = new JdbcValidationRepositories(ds);
        return new Fixture(tmp, r.clients(), r.projects(), r.runs(), r.datasets(), r.audit(), r.setups(),
                v.checks(), v.findings(), v.rules(), new JdbcReconRepository(ds), new JdbcCleaningRepository(ds));
    }
}
