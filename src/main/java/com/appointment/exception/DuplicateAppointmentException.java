package com.appointment.exception;

/**
 * Thrown when an appointment booking is attempted that duplicates an existing
 * active appointment for the same customer, dealership, and scheduled time.
 */
public class DuplicateAppointmentException extends RuntimeException {
    public DuplicateAppointmentException(String message) {
        super(message);
    }
}
