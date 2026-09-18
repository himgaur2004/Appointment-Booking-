package com.appointment.scheduler;

import com.appointment.dto.CreateAppointmentRequest;
import com.appointment.entity.Reminder;
import com.appointment.enums.ReminderStatus;
import com.appointment.enums.ReminderType;
import com.appointment.repository.AppointmentRepository;
import com.appointment.repository.DealershipRepository;
import com.appointment.repository.ReminderRepository;
import com.appointment.entity.Dealership;
import com.appointment.service.AppointmentService;
import com.appointment.service.ReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that simulates the full appointment -> reminder -> notification flow.
 * The scheduler is disabled; we invoke ReminderService.processDueReminders() directly.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Reminder Scheduler Integration Tests")
class ReminderSchedulerIntegrationTest {

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private ReminderService reminderService;

    @Autowired
    private DealershipRepository dealershipRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ReminderRepository reminderRepository;

    private Long dealershipId;

    @BeforeEach
    void setUp() {
        reminderRepository.deleteAll();
        appointmentRepository.deleteAll();
        dealershipRepository.deleteAll();

        Dealership dealership = Dealership.builder()
                .name("Scheduler Test Dealership")
                .address("456 Test Ave")
                .timezone("UTC")
                .build();
        dealership = dealershipRepository.save(dealership);
        dealershipId = dealership.getId();
    }

    @Test
    @DisplayName("End-to-end: Create appointment -> reminder becomes due -> notification sent")
    void endToEndReminderFlow() {
        // 1. Create an appointment far enough in the future for both reminders
        Instant scheduledAt = Instant.now().plus(48, ChronoUnit.HOURS);
        CreateAppointmentRequest request = new CreateAppointmentRequest(
                dealershipId, "E2E Customer", "e2e@example.com", null,
                "2024 BMW X5", "Full Service", scheduledAt);

        var response = appointmentService.createAppointment(request);
        Long appointmentId = response.id();

        // 2. Verify reminders were created as PENDING
        List<Reminder> reminders = reminderRepository.findByAppointmentId(appointmentId);
        assertThat(reminders).hasSize(2);
        assertThat(reminders).allMatch(r -> r.getStatus() == ReminderStatus.PENDING);

        // 3. Manually set the 24h reminder's scheduled_at to the past to simulate it becoming due
        Reminder reminder24h = reminders.stream()
                .filter(r -> r.getReminderType() == ReminderType.HOURS_24)
                .findFirst().orElseThrow();
        reminder24h.setScheduledAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        reminderRepository.save(reminder24h);

        // 4. Trigger the reminder processing (simulates scheduler poll)
        int processed = reminderService.processDueReminders();

        // 5. Verify: 24h reminder was sent, 2h reminder still pending
        assertThat(processed).isEqualTo(1);

        Reminder sent24h = reminderRepository.findById(reminder24h.getId()).orElseThrow();
        assertThat(sent24h.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(sent24h.getSentAt()).isNotNull();

        Reminder still2h = reminders.stream()
                .filter(r -> r.getReminderType() == ReminderType.HOURS_2)
                .findFirst().orElseThrow();
        Reminder fresh2h = reminderRepository.findById(still2h.getId()).orElseThrow();
        assertThat(fresh2h.getStatus()).isEqualTo(ReminderStatus.PENDING);
    }

    @Test
    @DisplayName("Idempotency: Running scheduler multiple times doesn't duplicate reminders")
    void schedulerIdempotency() {
        // Create appointment
        Instant scheduledAt = Instant.now().plus(48, ChronoUnit.HOURS);
        CreateAppointmentRequest request = new CreateAppointmentRequest(
                dealershipId, "Idempotency Customer", "idem@example.com", null,
                "Vehicle", "Service", scheduledAt);

        var response = appointmentService.createAppointment(request);
        Long appointmentId = response.id();

        // Make 24h reminder due
        Reminder reminder24h = reminderRepository.findByAppointmentId(appointmentId)
                .stream()
                .filter(r -> r.getReminderType() == ReminderType.HOURS_24)
                .findFirst().orElseThrow();
        reminder24h.setScheduledAt(Instant.now().minus(5, ChronoUnit.MINUTES));
        reminderRepository.save(reminder24h);

        // Process reminders THREE times
        reminderService.processDueReminders();
        reminderService.processDueReminders();
        reminderService.processDueReminders();

        // Verify exactly 2 reminders total (not more)
        List<Reminder> allReminders = reminderRepository.findByAppointmentId(appointmentId);
        assertThat(allReminders).hasSize(2);

        // The 24h reminder should be SENT exactly once
        Reminder sent = reminderRepository.findByAppointmentIdAndReminderType(
                appointmentId, ReminderType.HOURS_24).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(ReminderStatus.SENT);
    }

    @Test
    @DisplayName("Cancelled appointment: Scheduler does not send reminders")
    void cancelledAppointmentNoReminders() {
        // Create and cancel appointment
        Instant scheduledAt = Instant.now().plus(48, ChronoUnit.HOURS);
        CreateAppointmentRequest request = new CreateAppointmentRequest(
                dealershipId, "Cancel Test", "cancel@example.com", null,
                "Vehicle", "Service", scheduledAt);

        var response = appointmentService.createAppointment(request);
        Long appointmentId = response.id();

        // Cancel it
        appointmentService.cancelAppointment(appointmentId);

        // All reminders should already be CANCELLED by the service
        List<Reminder> reminders = reminderRepository.findByAppointmentId(appointmentId);
        assertThat(reminders).allMatch(r -> r.getStatus() == ReminderStatus.CANCELLED);

        // Running the scheduler should process 0 reminders
        int processed = reminderService.processDueReminders();
        assertThat(processed).isEqualTo(0);
    }

    @Test
    @DisplayName("Short-notice appointment: Only applicable reminders created")
    void shortNoticeAppointment() {
        // Create appointment only 5 hours away (only 2h reminder should be created)
        Instant scheduledAt = Instant.now().plus(5, ChronoUnit.HOURS);
        CreateAppointmentRequest request = new CreateAppointmentRequest(
                dealershipId, "Short Notice", "short@example.com", null,
                "Vehicle", "Service", scheduledAt);

        var response = appointmentService.createAppointment(request);
        Long appointmentId = response.id();

        List<Reminder> reminders = reminderRepository.findByAppointmentId(appointmentId);
        assertThat(reminders).hasSize(1);
        assertThat(reminders.get(0).getReminderType()).isEqualTo(ReminderType.HOURS_2);
    }
}
