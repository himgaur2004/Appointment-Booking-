package com.appointment.scheduler;

import com.appointment.service.ReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for due reminders and triggers notification sending.
 *
 * The scheduler is conditionally enabled via the 'reminder.scheduler.enabled' property,
 * allowing it to be disabled in tests and non-primary instances.
 */
@Component
@ConditionalOnProperty(name = "reminder.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ReminderScheduler {

    private final ReminderService reminderService;

    @Scheduled(fixedDelayString = "${reminder.scheduler.poll-interval-ms:60000}")
    public void pollAndSendReminders() {
        log.info("Reminder scheduler polling for due reminders");

        try {
            int processed = reminderService.processDueReminders();
            if (processed > 0) {
                log.info("Scheduler cycle complete: processed {} reminder(s)", processed);
            } else {
                log.debug("No due reminders found");
            }
        } catch (Exception e) {
            log.error("Scheduler execution failed: {}", e.getMessage(), e);
        }
    }
}
