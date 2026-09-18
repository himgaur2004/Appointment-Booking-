package com.appointment.exception;

/**
 * Thrown when an appointment is not found by ID.
 */
public class AppointmentNotFoundException extends RuntimeException {

    public AppointmentNotFoundException(Long id) {
        super("Appointment not found with id: " + id);
    }
}
