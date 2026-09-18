package com.appointment.dto;

import com.appointment.entity.Reminder;
import com.appointment.enums.ReminderStatus;
import com.appointment.enums.ReminderType;

import java.time.Instant;

/**
 * Response payload for reminder details.
 */
public record ReminderResponse(
        Long id,
        Long appointmentId,
        ReminderType reminderType,
        ReminderStatus status,
        Instant scheduledAt,
        Instant sentAt,
        int retryCount,
        String errorMessage,
        Instant createdAt
) {
    public static ReminderResponse from(Reminder reminder) {
        return new ReminderResponse(
                reminder.getId(),
                reminder.getAppointment().getId(),
                reminder.getReminderType(),
                reminder.getStatus(),
                reminder.getScheduledAt(),
                reminder.getSentAt(),
                reminder.getRetryCount(),
                reminder.getErrorMessage(),
                reminder.getCreatedAt()
        );
    }
}
