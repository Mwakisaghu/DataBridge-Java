package com.databridge.core.service;

/** The requested client, project, run or dataset does not exist. */
public class NotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public NotFoundException(String what, String id) { super(what + " not found: " + id); }
}
