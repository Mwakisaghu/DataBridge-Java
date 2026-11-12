package com.databridge.store.jdbc;

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * A fresh H2 database in SQL Server mode, migrated by the same {@link SchemaMigrator}
 * the application runs at startup. Tests only; production runs on SQL Server.
 */
final class TestDatabase {

    private TestDatabase() { }

    static JdbcDataSource empty() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1");
        return ds;
    }

    static DataSource create() {
        JdbcDataSource ds = empty();
        new SchemaMigrator(ds).migrate();
        return ds;
    }
}
