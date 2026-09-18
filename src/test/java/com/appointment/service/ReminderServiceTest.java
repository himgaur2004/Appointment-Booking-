package com.appointment.service;

import com.appointment.dto.NotificationPayload;
import com.appointment.entity.Appointment;
import com.appointment.entity.Dealership;
import com.appointment.entity.Reminder;
import com.appointment.enums.AppointmentStatus;
import com.appointment.enums.ReminderStatus;
import com.appointment.enums.ReminderType;
import com.appointment.repository.ReminderRepository;
import com.appointment.service.notification.NotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReminderService Unit Tests")
class ReminderServiceTest {

    @Mock
    private ReminderRepository reminderRepository;

    @Mock
    private NotificationSender notificationSender;

    @InjectMocks
    private ReminderService reminderService;

    private Dealership testDealership;
    private Appointment testAppointment;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(reminderService, "batchSize", 100);
        ReflectionTestUtils.setField(reminderService, "maxRetryCount", 3);

        testDealership = Dealership.builder()
                .id(1L)
                .name("Test Dealership")
                .timezone("UTC")
                .createdAt(Instant.now())
                .build();

        testAppointment = Appointment.builder()
                .id(1L)
                .dealership(testDealership)
                .customerName("John Doe")
                .customerEmail("john@example.com")
                .vehicleInfo("2024 Toyota Camry")
                .serviceType("Oil Change")
                .scheduledAt(Instant.now().plus(2, ChronoUnit.HOURS))
                .status(AppointmentStatus.SCHEDULED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("Process Due Reminders")
    class ProcessDueRemindersTests {

        @Test
        @DisplayName("Should send notification and mark reminder as SENT")
        void shouldProcessDueReminder() {
            Reminder reminder = Reminder.builder()
                    .id(1L)
                    .appointment(testAppointment)
                    .reminderType(ReminderType.HOURS_24)
                    .status(ReminderStatus.PENDING)
                    .scheduledAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .createdAt(Instant.now())
                    .build();

            when(reminderRepository.findDueReminderIdsWithLock(eq(ReminderStatus.PENDING.name()), any(Instant.class), anyInt()))
                    .thenReturn(List.of(1L));
            when(reminderRepository.findAllByIdWithDetails(List.of(1L)))
                    .thenReturn(List.of(reminder));
            when(reminderRepository.findById(1L)).thenReturn(Optional.of(reminder));
            when(reminderRepository.save(any(Reminder.class))).thenReturn(reminder);
            when(reminderRepository.findRetryableReminders(anyInt(), any(Instant.class)))
                    .thenReturn(List.of());

            int processed = reminderService.processDueReminders();

            assertThat(processed).isEqualTo(1);

            // Verify notification was sent
            ArgumentCaptor<NotificationPayload> payloadCaptor =
                    ArgumentCaptor.forClass(NotificationPayload.class);
            verify(notificationSender).send(payloadCaptor.capture());

            NotificationPayload payload = payloadCaptor.getValue();
            assertThat(payload.customerName()).isEqualTo("John Doe");
            assertThat(payload.customerContact()).isEqualTo("john@example.com");
            assertThat(payload.reminderType()).isEqualTo("24-hour");

            // Verify reminder was marked SENT
            assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.SENT);
            assertThat(reminder.getSentAt()).isNotNull();
        }

        @Test
        @DisplayName("Should return 0 when no due reminders")
        void shouldReturnZeroWhenNoDue() {
            when(reminderRepository.findDueReminderIdsWithLock(eq(ReminderStatus.PENDING.name()), any(Instant.class), anyInt()))
                    .thenReturn(List.of());

            int processed = reminderService.processDueReminders();
            assertThat(processed).isEqualTo(0);
            verify(notificationSender, never()).send(any());
        }

        @Test
        @DisplayName("Should mark as FAILED when notification sender throws")
        void shouldHandleNotificationFailure() {
            Reminder reminder = Reminder.builder()
                    .id(2L)
                    .appointment(testAppointment)
                    .reminderType(ReminderType.HOURS_2)
                    .status(ReminderStatus.PENDING)
                    .scheduledAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .retryCount(0)
                    .createdAt(Instant.now())
                    .build();

            when(reminderRepository.findDueReminderIdsWithLock(eq(ReminderStatus.PENDING.name()), any(Instant.class), anyInt()))
                    .thenReturn(List.of(2L));
            when(reminderRepository.findAllByIdWithDetails(List.of(2L)))
                    .thenReturn(List.of(reminder));
            when(reminderRepository.findById(2L)).thenReturn(Optional.of(reminder));
            when(reminderRepository.save(any(Reminder.class))).thenReturn(reminder);
            when(reminderRepository.findRetryableReminders(anyInt(), any(Instant.class)))
                    .thenReturn(List.of());

            doThrow(new RuntimeException("SMS gateway down"))
                    .when(notificationSender).send(any());

            int processed = reminderService.processDueReminders();

            // Failed, so processed count is 0
            assertThat(processed).isEqualTo(0);
            assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.FAILED);
            assertThat(reminder.getRetryCount()).isEqualTo(1);
            assertThat(reminder.getErrorMessage()).isEqualTo("SMS gateway down");
        }

        @Test
        @DisplayName("Should skip already processed reminders")
        void shouldSkipAlreadyProcessed() {
            Reminder reminderInBatch = Reminder.builder()
                    .id(3L)
                    .appointment(testAppointment)
                    .reminderType(ReminderType.HOURS_24)
                    .status(ReminderStatus.PENDING)
                    .scheduledAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .createdAt(Instant.now())
                    .build();

            Reminder alreadyProcessedInDb = Reminder.builder()
                    .id(3L)
                    .appointment(testAppointment)
                    .reminderType(ReminderType.HOURS_24)
                    .status(ReminderStatus.SENT)
                    .scheduledAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .createdAt(Instant.now())
                    .build();

            when(reminderRepository.findDueReminderIdsWithLock(eq(ReminderStatus.PENDING.name()), any(Instant.class), anyInt()))
                    .thenReturn(List.of(3L));
            when(reminderRepository.findAllByIdWithDetails(List.of(3L)))
                    .thenReturn(List.of(reminderInBatch));
            when(reminderRepository.findById(3L)).thenReturn(Optional.of(alreadyProcessedInDb));
            when(reminderRepository.findRetryableReminders(anyInt(), any(Instant.class)))
                    .thenReturn(List.of());

            int processed = reminderService.processDueReminders();

            // Should be skipped - no notification sent
            assertThat(processed).isEqualTo(0);
            verify(notificationSender, never()).send(any());
        }

        @Test
        @DisplayName("Should cancel reminder if appointment was cancelled")
        void shouldCancelReminderForCancelledAppointment() {
            testAppointment.setStatus(AppointmentStatus.CANCELLED);

            Reminder reminder = Reminder.builder()
                    .id(4L)
                    .appointment(testAppointment)
                    .reminderType(ReminderType.HOURS_24)
                    .status(ReminderStatus.PENDING)
                    .scheduledAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .createdAt(Instant.now())
                    .build();

            when(reminderRepository.findDueReminderIdsWithLock(eq(ReminderStatus.PENDING.name()), any(Instant.class), anyInt()))
                    .thenReturn(List.of(4L));
            when(reminderRepository.findAllByIdWithDetails(List.of(4L)))
                    .thenReturn(List.of(reminder));
            when(reminderRepository.findById(4L)).thenReturn(Optional.of(reminder));
            when(reminderRepository.save(any(Reminder.class))).thenReturn(reminder);
            when(reminderRepository.findRetryableReminders(anyInt(), any(Instant.class)))
                    .thenReturn(List.of());

            reminderService.processDueReminders();

            assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.CANCELLED);
            verify(notificationSender, never()).send(any());
        }
    }

    @Nested
    @DisplayName("Notification Payload")
    class NotificationPayloadTests {

        @Test
        @DisplayName("Should use email as contact when available")
        void shouldUseEmail() {
            testAppointment.setCustomerEmail("john@example.com");
            testAppointment.setCustomerPhone("+1-555-0100");

            Reminder reminder = Reminder.builder()
                    .id(5L)
                    .appointment(testAppointment)
                    .reminderType(ReminderType.HOURS_24)
                    .status(ReminderStatus.PENDING)
                    .scheduledAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .createdAt(Instant.now())
                    .build();

            when(reminderRepository.findDueReminderIdsWithLock(eq(ReminderStatus.PENDING.name()), any(Instant.class), anyInt()))
                    .thenReturn(List.of(5L));
            when(reminderRepository.findAllByIdWithDetails(List.of(5L)))
                    .thenReturn(List.of(reminder));
            when(reminderRepository.findById(5L)).thenReturn(Optional.of(reminder));
            when(reminderRepository.save(any(Reminder.class))).thenReturn(reminder);
            when(reminderRepository.findRetryableReminders(anyInt(), any(Instant.class)))
                    .thenReturn(List.of());

            reminderService.processDueReminders();

            ArgumentCaptor<NotificationPayload> captor =
                    ArgumentCaptor.forClass(NotificationPayload.class);
            verify(notificationSender).send(captor.capture());

            assertThat(captor.getValue().customerContact()).isEqualTo("john@example.com");
        }

        @Test
        @DisplayName("Should use phone when email is not available")
        void shouldUsePhone() {
            testAppointment.setCustomerEmail(null);
            testAppointment.setCustomerPhone("+1-555-0100");

            Reminder reminder = Reminder.builder()
                    .id(6L)
                    .appointment(testAppointment)
                    .reminderType(ReminderType.HOURS_2)
                    .status(ReminderStatus.PENDING)
                    .scheduledAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .createdAt(Instant.now())
                    .build();

            when(reminderRepository.findDueReminderIdsWithLock(eq(ReminderStatus.PENDING.name()), any(Instant.class), anyInt()))
                    .thenReturn(List.of(6L));
            when(reminderRepository.findAllByIdWithDetails(List.of(6L)))
                    .thenReturn(List.of(reminder));
            when(reminderRepository.findById(6L)).thenReturn(Optional.of(reminder));
            when(reminderRepository.save(any(Reminder.class))).thenReturn(reminder);
            when(reminderRepository.findRetryableReminders(anyInt(), any(Instant.class)))
                    .thenReturn(List.of());

            reminderService.processDueReminders();

            ArgumentCaptor<NotificationPayload> captor =
                    ArgumentCaptor.forClass(NotificationPayload.class);
            verify(notificationSender).send(captor.capture());

            assertThat(captor.getValue().customerContact()).isEqualTo("+1-555-0100");
            assertThat(captor.getValue().reminderType()).isEqualTo("2-hour");
        }
    }
}
