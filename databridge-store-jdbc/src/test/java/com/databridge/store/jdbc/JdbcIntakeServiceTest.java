package com.databridge.store.jdbc;

import com.databridge.core.service.Fixture;
import com.databridge.core.service.IntakeServiceContract;

import java.nio.file.Path;

/** The same intake contract the in-memory store passes, run against the database schema. */
class JdbcIntakeServiceTest extends IntakeServiceContract {
    @Override
    protected Fixture fixture(Path tmp) {
        JdbcRepositories r = new JdbcRepositories(TestDatabase.create());
        return new Fixture(tmp, r.clients(), r.projects(), r.runs(), r.datasets(), r.audit(), r.setups());
    }
}
