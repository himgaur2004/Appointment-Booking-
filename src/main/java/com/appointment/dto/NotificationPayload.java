package com.appointment.dto;

/**
 * Payload sent to the notification sender for each reminder.
 */
public record NotificationPayload(
        Long appointmentId,
        Long reminderId,
        String customerName,
        String customerContact,
        String reminderType,
        String serviceType,
        String vehicleInfo,
        String scheduledAt,
        String dealershipName,
        String message
) {
}
