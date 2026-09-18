package com.appointment.exception;

/**
 * Thrown when a referenced dealership does not exist.
 */
public class DealershipNotFoundException extends RuntimeException {

    public DealershipNotFoundException(Long id) {
        super("Dealership not found with id: " + id);
    }
}
