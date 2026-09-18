package com.appointment.enums;

/**
 * Types of reminders that can be sent for an appointment.
 * Each appointment can have at most one reminder of each type.
 */
public enum ReminderType {

    /** Sent 24 hours before the appointment */
    HOURS_24(24),

    /** Sent 2 hours before the appointment */
    HOURS_2(2);

    private final int hoursBefore;

    ReminderType(int hoursBefore) {
        this.hoursBefore = hoursBefore;
    }

    public int getHoursBefore() {
        return hoursBefore;
    }
}
