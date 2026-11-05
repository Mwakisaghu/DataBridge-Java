package com.databridge.core.workspace;

/** A workspace could not be created, filled or destroyed. */
public class WorkspaceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WorkspaceException(String message) { super(message); }
    public WorkspaceException(String message, Throwable cause) { super(message, cause); }
}
