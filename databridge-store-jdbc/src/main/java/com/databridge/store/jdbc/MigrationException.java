package com.databridge.store.jdbc;

/** The database schema could not be brought up to date. The application must not start. */
public class MigrationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public MigrationException(String message) { super(message); }
    public MigrationException(String message, Throwable cause) { super(message, cause); }
}
