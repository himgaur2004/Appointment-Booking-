package com.appointment.service.notification;

import com.appointment.dto.NotificationPayload;

/**
 * Interface for sending reminder notifications.
 * Implementations can send via email, SMS, push notification, etc.
 * The stub implementation simply logs the payload.
 */
public interface NotificationSender {

    /**
     * Send a reminder notification.
     *
     * @param payload the notification details
     * @throws RuntimeException if the notification fails to send
     */
    void send(NotificationPayload payload);
}
