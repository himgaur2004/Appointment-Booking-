package com.appointment.service.notification;

import com.appointment.dto.NotificationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stub implementation of NotificationSender.
 * Logs the notification payload instead of actually sending it.
 * In production, this would be replaced with an email/SMS service.
 */
@Component
@Slf4j
public class StubNotificationSender implements NotificationSender {

    @Override
    public void send(NotificationPayload payload) {
        log.info("========================================");
        log.info("Sending reminder notification");
        log.info("========================================");
        log.info("  Reminder ID   : {}", payload.reminderId());
        log.info("  Appointment ID: {}", payload.appointmentId());
        log.info("  To            : {} ({})", payload.customerName(), payload.customerContact());
        log.info("  Type          : {} reminder", payload.reminderType());
        log.info("  Service       : {}", payload.serviceType());
        log.info("  Vehicle       : {}", payload.vehicleInfo());
        log.info("  Scheduled At  : {}", payload.scheduledAt());
        log.info("  Dealership    : {}", payload.dealershipName());
        log.info("  Message       : {}", payload.message());
        log.info("========================================");
    }
}
