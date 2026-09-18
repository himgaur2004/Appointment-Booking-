package com.appointment.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Standardized error response payload.
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        Instant timestamp,
        Map<String, String> fieldErrors
) {
    public ErrorResponse(int status, String error, String message) {
        this(status, error, message, Instant.now(), null);
    }

    public ErrorResponse(int status, String error, String message, Map<String, String> fieldErrors) {
        this(status, error, message, Instant.now(), fieldErrors);
    }
}
