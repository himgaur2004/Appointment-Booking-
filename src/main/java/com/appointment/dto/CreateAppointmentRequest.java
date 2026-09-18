package com.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Request payload for creating a new appointment.
 */
public record CreateAppointmentRequest(

        @NotNull(message = "Dealership ID is required")
        Long dealershipId,

        @NotBlank(message = "Customer name is required")
        String customerName,

        String customerEmail,

        String customerPhone,

        String vehicleInfo,

        @NotBlank(message = "Service type is required")
        String serviceType,

        @NotNull(message = "Scheduled time is required")
        Instant scheduledAt
) {
    /**
     * Custom validation: at least one contact method must be provided.
     */
    public boolean hasValidContact() {
        return (customerEmail != null && !customerEmail.isBlank())
                || (customerPhone != null && !customerPhone.isBlank());
    }
}
