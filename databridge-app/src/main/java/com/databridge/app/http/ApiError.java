package com.databridge.app.http;

/** Error body returned by every endpoint. */
public record ApiError(String status, String error) {
    public static ApiError of(String message) {
        return new ApiError("ERROR", message);
    }
}
