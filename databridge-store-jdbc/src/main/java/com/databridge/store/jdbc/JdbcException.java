package com.databridge.store.jdbc;

/** A database call failed. Wraps SQLException so services stay JDBC-free. */
public class JdbcException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public JdbcException(String message, Throwable cause) { super(message, cause); }
}
