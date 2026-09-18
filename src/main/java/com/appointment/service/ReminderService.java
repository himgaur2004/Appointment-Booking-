package com.appointment.service;

import com.appointment.dto.NotificationPayload;
import com.appointment.entity.Appointment;
import com.appointment.entity.Reminder;
import com.appointment.enums.AppointmentStatus;
import com.appointment.enums.ReminderStatus;
import com.appointment.enums.ReminderType;
import com.appointment.repository.ReminderRepository;
import com.appointment.service.notification.NotificationSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Service for polling due reminders, dispatching notifications,
 * and handling retries for failed deliveries.
 *
 * Concurrency & Idempotency:
 * - findDueReminders locks rows with PESSIMISTIC_WRITE (FOR UPDATE)
 * - Status checks ensure already processed reminders are skipped
 * - Max retries prevent poison pill reminders from looping infinitely
 */
@Service
@Slf4j
public class ReminderService {

    private final ReminderRepository reminderRepository;
    private final NotificationSender notificationSender;

    @Value("${reminder.scheduler.batch-size:100}")
    private int batchSize;

    @Value("${reminder.scheduler.max-retry-count:3}")
    private int maxRetryCount;

    private static final DateTimeFormatter DISPLAY_FORMATTER =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a z")
                    .withZone(ZoneId.of("UTC"));

    public ReminderService(ReminderRepository reminderRepository,
                           NotificationSender notificationSender) {
        this.reminderRepository = reminderRepository;
        this.notificationSender = notificationSender;
    }

    /**
     * Process all due reminders in batch.
     * Returns the number of reminders successfully processed.
     */
    @Transactional
    public int processDueReminders() {
        Instant now = Instant.now();

        // Fetch due PENDING reminders using PostgreSQL FOR UPDATE SKIP LOCKED
        List<Long> lockedIds = reminderRepository
                .findDueReminderIdsWithLock(ReminderStatus.PENDING.name(), now, batchSize);

        if (lockedIds.isEmpty()) {
            return 0;
        }

        List<Reminder> batch = reminderRepository.findAllByIdWithDetails(lockedIds);

        log.info("Found {} due reminder(s), processing batch of {}",
                lockedIds.size(), batch.size());

        int successCount = 0;
        for (Reminder reminder : batch) {
            try {
                if (processReminder(reminder)) {
                    successCount++;
                }
            } catch (Exception e) {
                log.error("Failed to process reminder id={}: {}",
                        reminder.getId(), e.getMessage(), e);
            }
        }

        // Also process retryable failed reminders
        int retried = processRetryableReminders(now);

        log.info("Reminder batch complete: {} sent, {} retried", successCount, retried);
        return successCount;
    }

    /**
     * Process a single reminder: send notification and update status.
     * Returns true if the notification was sent successfully, false otherwise.
     */
    @Transactional
    public boolean processReminder(Reminder reminder) {
        if (reminder == null) {
            return false;
        }

        // Double-check the reminder hasn't been processed already (defensive)
        Reminder fresh = reminderRepository.findById(reminder.getId()).orElse(null);
        if (fresh == null || fresh.getStatus() != ReminderStatus.PENDING) {
            log.info("Reminder id={} already processed or missing, skipping", reminder.getId());
            return false;
        }

        // Use appointment from reminder (which has fetch joins) or from fresh
        Appointment appointment = reminder.getAppointment() != null
                ? reminder.getAppointment()
                : fresh.getAppointment();

        // Don't send reminders for cancelled/completed appointments
        if (appointment != null && appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            log.info("Appointment id={} is {}, cancelling reminder id={}",
                    appointment.getId(), appointment.getStatus(), fresh.getId());
            fresh.setStatus(ReminderStatus.CANCELLED);
            reminder.setStatus(ReminderStatus.CANCELLED);
            reminderRepository.save(fresh);
            return false;
        }

        try {
            // Build and send notification
            NotificationPayload payload = buildPayload(fresh, appointment);
            notificationSender.send(payload);

            // Mark as sent
            fresh.setStatus(ReminderStatus.SENT);
            fresh.setSentAt(Instant.now());
            reminder.setStatus(ReminderStatus.SENT);
            reminder.setSentAt(fresh.getSentAt());
            reminderRepository.save(fresh);

            log.info("Reminder sent: id={}, type={}, appointment id={}, customer={}",
                    fresh.getId(), fresh.getReminderType(),
                    appointment != null ? appointment.getId() : null,
                    appointment != null ? appointment.getCustomerName() : null);
            return true;

        } catch (Exception e) {
            int newRetryCount = fresh.getRetryCount() + 1;
            fresh.setRetryCount(newRetryCount);
            fresh.setErrorMessage(e.getMessage());
            reminder.setRetryCount(newRetryCount);
            reminder.setErrorMessage(e.getMessage());

            if (newRetryCount >= maxRetryCount) {
                // Transition to DEAD_LETTER after exhausting all retries
                fresh.setStatus(ReminderStatus.DEAD_LETTER);
                reminder.setStatus(ReminderStatus.DEAD_LETTER);
                log.error("Reminder reached DEAD_LETTER state: id={}, type={}, attempts={}/{}, error={}",
                        fresh.getId(), fresh.getReminderType(), newRetryCount, maxRetryCount, e.getMessage());
            } else {
                fresh.setStatus(ReminderStatus.FAILED);
                reminder.setStatus(ReminderStatus.FAILED);
                // Exponential backoff: 2^(newRetryCount - 1) * 60 seconds (1m, 2m, 4m...)
                long backoffSeconds = (long) Math.pow(2, newRetryCount - 1) * 60L;
                Instant nextRetryTime = Instant.now().plusSeconds(backoffSeconds);
                fresh.setScheduledAt(nextRetryTime);
                reminder.setScheduledAt(nextRetryTime);
                log.warn("Reminder delivery failed (retry scheduled at {} with backoff {}s): id={}, attempt={}/{}",
                        nextRetryTime, backoffSeconds, fresh.getId(), newRetryCount, maxRetryCount);
            }
            reminderRepository.save(fresh);
            return false;
        }
    }

    /**
     * Retry failed reminders that haven't exceeded the max retry count.
     */
    @Transactional
    public int processRetryableReminders(Instant now) {
        List<Reminder> retryable = reminderRepository
                .findRetryableReminders(maxRetryCount, now);

        if (retryable.isEmpty()) {
            return 0;
        }

        log.info("Found {} retryable failed reminder(s)", retryable.size());

        int retried = 0;
        for (Reminder reminder : retryable) {
            reminder.setStatus(ReminderStatus.PENDING);
            reminderRepository.save(reminder);
            try {
                if (processReminder(reminder)) {
                    retried++;
                }
            } catch (Exception e) {
                log.error("Retry failed for reminder id={}: {}", reminder.getId(), e.getMessage());
            }
        }

        return retried;
    }

    // ==================== Private Helpers ====================

    private NotificationPayload buildPayload(Reminder reminder, Appointment appointment) {
        String contact = appointment != null && appointment.getCustomerEmail() != null
                ? appointment.getCustomerEmail()
                : (appointment != null ? appointment.getCustomerPhone() : "N/A");

        String humanReadableType = reminder.getReminderType() == ReminderType.HOURS_24
                ? "24-hour" : "2-hour";

        Instant scheduledTime = appointment != null ? appointment.getScheduledAt() : reminder.getScheduledAt();
        String formattedTime = scheduledTime != null ? DISPLAY_FORMATTER.format(scheduledTime) : "N/A";
        String dealershipName = (appointment != null && appointment.getDealership() != null)
                ? appointment.getDealership().getName() : "Dealership";
        String customerName = appointment != null ? appointment.getCustomerName() : "Customer";
        String serviceType = appointment != null ? appointment.getServiceType() : "Service";
        String vehicleInfo = appointment != null && appointment.getVehicleInfo() != null
                ? appointment.getVehicleInfo() : "N/A";

        String message = String.format(
                "Hi %s, this is your %s reminder for your %s appointment at %s on %s. " +
                        "Vehicle: %s. Please arrive 10 minutes early.",
                customerName,
                humanReadableType,
                serviceType,
                dealershipName,
                formattedTime,
                vehicleInfo
        );

        return new NotificationPayload(
                appointment != null ? appointment.getId() : null,
                reminder.getId(),
                customerName,
                contact,
                humanReadableType,
                serviceType,
                vehicleInfo,
                formattedTime,
                dealershipName,
                message
        );
    }
}
