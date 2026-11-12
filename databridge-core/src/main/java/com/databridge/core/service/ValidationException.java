package com.databridge.core.service;

/** A request is not acceptable (bad code, duplicate, closed run, invalid setup value). */
public class ValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ValidationException(String message) { super(message); }
}
