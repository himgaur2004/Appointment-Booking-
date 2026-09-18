package com.appointment.dto;

import com.appointment.entity.Appointment;
import com.appointment.entity.Reminder;
import com.appointment.enums.AppointmentStatus;

import java.time.Instant;
import java.util.List;

/**
 * Response payload for appointment details.
 */
public record AppointmentResponse(
        Long id,
        Long dealershipId,
        String dealershipName,
        String customerName,
        String customerEmail,
        String customerPhone,
        String vehicleInfo,
        String serviceType,
        Instant scheduledAt,
        AppointmentStatus status,
        List<ReminderResponse> reminders,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Factory method to create response from entity and explicit reminder list.
     */
    public static AppointmentResponse from(Appointment appointment, List<Reminder> reminders) {
        List<ReminderResponse> reminderResponses = reminders != null
                ? reminders.stream()
                    .map(ReminderResponse::from)
                    .toList()
                : List.of();

        return new AppointmentResponse(
                appointment.getId(),
                appointment.getDealership().getId(),
                appointment.getDealership().getName(),
                appointment.getCustomerName(),
                appointment.getCustomerEmail(),
                appointment.getCustomerPhone(),
                appointment.getVehicleInfo(),
                appointment.getServiceType(),
                appointment.getScheduledAt(),
                appointment.getStatus(),
                reminderResponses,
                appointment.getCreatedAt(),
                appointment.getUpdatedAt()
        );
    }

    /**
     * Factory method to create response from entity.
     */
    public static AppointmentResponse from(Appointment appointment) {
        return from(appointment, appointment.getReminders());
    }

    /**
     * Factory method without reminders (for list views).
     */
    public static AppointmentResponse fromWithoutReminders(Appointment appointment) {
        return new AppointmentResponse(
                appointment.getId(),
                appointment.getDealership().getId(),
                appointment.getDealership().getName(),
                appointment.getCustomerName(),
                appointment.getCustomerEmail(),
                appointment.getCustomerPhone(),
                appointment.getVehicleInfo(),
                appointment.getServiceType(),
                appointment.getScheduledAt(),
                appointment.getStatus(),
                null,
                appointment.getCreatedAt(),
                appointment.getUpdatedAt()
        );
    }
}
