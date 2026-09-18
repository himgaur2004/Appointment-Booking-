package com.appointment.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Request payload for rescheduling an appointment to a new date/time.
 */
public record RescheduleAppointmentRequest(
        @NotNull(message = "New scheduled time is required")
        @Future(message = "New scheduled time must be in the future")
        Instant newScheduledAt
) {}
