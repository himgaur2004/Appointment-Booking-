package com.appointment.exception;

/**
 * Thrown when appointment creation/modification violates business rules.
 */
public class InvalidAppointmentException extends RuntimeException {

    public InvalidAppointmentException(String message) {
        super(message);
    }
}
