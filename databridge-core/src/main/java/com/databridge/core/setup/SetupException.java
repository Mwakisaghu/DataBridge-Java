package com.databridge.core.setup;

/** A setup file is missing, unreadable or holds an invalid value. */
public class SetupException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SetupException(String message) { super(message); }
    public SetupException(String message, Throwable cause) { super(message, cause); }
}
