package com.databridge.core.security;

/** Not signed in, wrong credentials, or not allowed. {@code forbidden} distinguishes 403 from 401. */
public class AuthException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean forbidden;

    public AuthException(String message, boolean forbidden) {
        super(message);
        this.forbidden = forbidden;
    }

    public boolean forbidden() {
        return forbidden;
    }
}
