package com.appointment.service;

import com.appointment.dto.AppointmentResponse;
import com.appointment.dto.CreateAppointmentRequest;
import com.appointment.entity.Appointment;
import com.appointment.entity.Dealership;
import com.appointment.entity.Reminder;
import com.appointment.enums.AppointmentStatus;
import com.appointment.enums.ReminderStatus;
import com.appointment.enums.ReminderType;
import com.appointment.exception.AppointmentNotFoundException;
import com.appointment.exception.DealershipNotFoundException;
import com.appointment.exception.InvalidAppointmentException;
import com.appointment.repository.AppointmentRepository;
import com.appointment.repository.DealershipRepository;
import com.appointment.repository.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentService Unit Tests")
class AppointmentServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private DealershipRepository dealershipRepository;

    @Mock
    private ReminderRepository reminderRepository;

    @InjectMocks
    private AppointmentService appointmentService;

    private Dealership testDealership;

    @BeforeEach
    void setUp() {
        testDealership = Dealership.builder()
                .id(1L)
                .name("Test Dealership")
                .timezone("UTC")
                .createdAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("Create Appointment")
    class CreateAppointmentTests {

        @Test
        @DisplayName("Should create appointment with both reminders when scheduled > 24h away")
        void shouldCreateBothReminders() {
            // Arrange
            Instant scheduledAt = Instant.now().plus(48, ChronoUnit.HOURS);
            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    1L, "John Doe", "john@example.com", null,
                    "2024 Toyota Camry", "Oil Change", scheduledAt);

            when(dealershipRepository.findById(1L)).thenReturn(Optional.of(testDealership));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> {
                Appointment a = invocation.getArgument(0);
                a.setId(1L);
                a.setCreatedAt(Instant.now());
                a.setUpdatedAt(Instant.now());
                return a;
            });
            when(reminderRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            AppointmentResponse response = appointmentService.createAppointment(request);

            // Assert
            assertThat(response).isNotNull();
            assertThat(response.customerName()).isEqualTo("John Doe");
            assertThat(response.status()).isEqualTo(AppointmentStatus.SCHEDULED);

            // Verify both reminders were created
            ArgumentCaptor<List<Reminder>> reminderListCaptor = ArgumentCaptor.forClass(List.class);
            verify(reminderRepository).saveAll(reminderListCaptor.capture());
            assertThat(reminderListCaptor.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("Should create only 2h reminder when scheduled 3-24h away")
        void shouldCreateOnly2hReminder() {
            // Arrange - appointment in 5 hours
            Instant scheduledAt = Instant.now().plus(5, ChronoUnit.HOURS);
            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    1L, "Jane Doe", "jane@example.com", null,
                    "2023 Honda Civic", "Tire Rotation", scheduledAt);

            when(dealershipRepository.findById(1L)).thenReturn(Optional.of(testDealership));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> {
                Appointment a = invocation.getArgument(0);
                a.setId(2L);
                a.setCreatedAt(Instant.now());
                a.setUpdatedAt(Instant.now());
                return a;
            });
            when(reminderRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            AppointmentResponse response = appointmentService.createAppointment(request);

            // Assert - only 1 reminder (the 2h one) should be created
            ArgumentCaptor<List<Reminder>> reminderListCaptor = ArgumentCaptor.forClass(List.class);
            verify(reminderRepository).saveAll(reminderListCaptor.capture());
            List<Reminder> createdReminders = reminderListCaptor.getValue();
            assertThat(createdReminders).hasSize(1);
            assertThat(createdReminders.get(0).getReminderType()).isEqualTo(ReminderType.HOURS_2);
        }

        @Test
        @DisplayName("Should create no reminders when scheduled < 2h away")
        void shouldCreateNoReminders() {
            // Arrange - appointment in 1 hour
            Instant scheduledAt = Instant.now().plus(1, ChronoUnit.HOURS);
            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    1L, "Bob", "bob@example.com", null,
                    "2022 Ford F-150", "Brake Check", scheduledAt);

            when(dealershipRepository.findById(1L)).thenReturn(Optional.of(testDealership));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> {
                Appointment a = invocation.getArgument(0);
                a.setId(3L);
                a.setCreatedAt(Instant.now());
                a.setUpdatedAt(Instant.now());
                return a;
            });

            // Act
            AppointmentResponse response = appointmentService.createAppointment(request);

            // Assert - no reminders should be created
            verify(reminderRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("Should reject appointment with no contact info")
        void shouldRejectNoContact() {
            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    1L, "No Contact", null, null,
                    "Vehicle", "Service", Instant.now().plus(48, ChronoUnit.HOURS));

            assertThatThrownBy(() -> appointmentService.createAppointment(request))
                    .isInstanceOf(InvalidAppointmentException.class)
                    .hasMessageContaining("contact method");
        }

        @Test
        @DisplayName("Should reject appointment in the past")
        void shouldRejectPastAppointment() {
            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    1L, "Past", "past@example.com", null,
                    "Vehicle", "Service", Instant.now().minus(1, ChronoUnit.HOURS));

            assertThatThrownBy(() -> appointmentService.createAppointment(request))
                    .isInstanceOf(InvalidAppointmentException.class)
                    .hasMessageContaining("future");
        }

        @Test
        @DisplayName("Should throw when dealership not found")
        void shouldThrowDealershipNotFound() {
            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    999L, "Test", "test@example.com", null,
                    "Vehicle", "Service", Instant.now().plus(48, ChronoUnit.HOURS));

            when(dealershipRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> appointmentService.createAppointment(request))
                    .isInstanceOf(DealershipNotFoundException.class);
        }

        @Test
        @DisplayName("Should accept phone number as sole contact method")
        void shouldAcceptPhoneOnly() {
            Instant scheduledAt = Instant.now().plus(48, ChronoUnit.HOURS);
            CreateAppointmentRequest request = new CreateAppointmentRequest(
                    1L, "Phone Only", null, "+1-555-0100",
                    "Vehicle", "Service", scheduledAt);

            when(dealershipRepository.findById(1L)).thenReturn(Optional.of(testDealership));
            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(invocation -> {
                Appointment a = invocation.getArgument(0);
                a.setId(4L);
                a.setCreatedAt(Instant.now());
                a.setUpdatedAt(Instant.now());
                return a;
            });
            when(reminderRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

            AppointmentResponse response = appointmentService.createAppointment(request);
            assertThat(response.customerPhone()).isEqualTo("+1-555-0100");
        }
    }

    @Nested
    @DisplayName("Cancel Appointment")
    class CancelAppointmentTests {

        @Test
        @DisplayName("Should cancel appointment and pending reminders")
        void shouldCancelWithReminders() {
            Appointment appointment = Appointment.builder()
                    .id(1L)
                    .dealership(testDealership)
                    .customerName("Test")
                    .customerEmail("test@example.com")
                    .serviceType("Service")
                    .scheduledAt(Instant.now().plus(48, ChronoUnit.HOURS))
                    .status(AppointmentStatus.SCHEDULED)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            Reminder pendingReminder = Reminder.builder()
                    .id(1L)
                    .appointment(appointment)
                    .reminderType(ReminderType.HOURS_24)
                    .status(ReminderStatus.PENDING)
                    .scheduledAt(Instant.now().plus(24, ChronoUnit.HOURS))
                    .createdAt(Instant.now())
                    .build();

            when(appointmentRepository.findByIdWithDealership(1L))
                    .thenReturn(Optional.of(appointment));
            when(reminderRepository.findByAppointmentIdAndStatus(1L, ReminderStatus.PENDING))
                    .thenReturn(List.of(pendingReminder));
            when(appointmentRepository.save(any(Appointment.class))).thenReturn(appointment);
            when(reminderRepository.saveAll(anyList())).thenReturn(List.of(pendingReminder));
            when(reminderRepository.findByAppointmentId(1L)).thenReturn(List.of(pendingReminder));

            AppointmentResponse response = appointmentService.cancelAppointment(1L);

            assertThat(response.status()).isEqualTo(AppointmentStatus.CANCELLED);
            assertThat(pendingReminder.getStatus()).isEqualTo(ReminderStatus.CANCELLED);
        }

        @Test
        @DisplayName("Should throw when cancelling already cancelled appointment")
        void shouldThrowOnDoubleCancellation() {
            Appointment appointment = Appointment.builder()
                    .id(1L)
                    .dealership(testDealership)
                    .status(AppointmentStatus.CANCELLED)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(appointmentRepository.findByIdWithDealership(1L))
                    .thenReturn(Optional.of(appointment));

            assertThatThrownBy(() -> appointmentService.cancelAppointment(1L))
                    .isInstanceOf(InvalidAppointmentException.class)
                    .hasMessageContaining("already cancelled");
        }

        @Test
        @DisplayName("Should throw when appointment not found")
        void shouldThrowNotFound() {
            when(appointmentRepository.findByIdWithDealership(999L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> appointmentService.cancelAppointment(999L))
                    .isInstanceOf(AppointmentNotFoundException.class);
        }
    }
}
