package com.databridge.core.integration;

/** Business Central could not be reached or refused a call; the message says what to check. */
public class IntegrationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IntegrationException(String message) {
        super(message);
    }

    public IntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
