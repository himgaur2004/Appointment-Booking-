package com.appointment.service;

import com.appointment.dto.AppointmentResponse;
import com.appointment.dto.CreateAppointmentRequest;
import com.appointment.dto.ReminderResponse;
import com.appointment.dto.RescheduleAppointmentRequest;
import com.appointment.entity.Appointment;
import com.appointment.entity.Dealership;
import com.appointment.entity.Reminder;
import com.appointment.enums.AppointmentStatus;
import com.appointment.enums.ReminderStatus;
import com.appointment.enums.ReminderType;
import com.appointment.exception.AppointmentNotFoundException;
import com.appointment.exception.DealershipNotFoundException;
import com.appointment.exception.DuplicateAppointmentException;
import com.appointment.exception.InvalidAppointmentException;
import com.appointment.repository.AppointmentRepository;
import com.appointment.repository.DealershipRepository;
import com.appointment.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;

/**
 * Core service for managing appointments and eagerly creating reminder records.
 *
 * Design decisions:
 * - Reminders are created at appointment time (eager), not discovered later (lazy).
 *   This ensures no reminders are missed even during scheduler outages.
 * - Only applicable reminders are created based on how far away the appointment is.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final DealershipRepository dealershipRepository;
    private final ReminderRepository reminderRepository;

    /**
     * Create a new appointment and eagerly schedule applicable reminders.
     */
    @Transactional
    public AppointmentResponse createAppointment(CreateAppointmentRequest request) {
        return createAppointment(request, null);
    }

    @Transactional
    public AppointmentResponse createAppointment(CreateAppointmentRequest request, String idempotencyKey) {
        // Idempotency check: return existing appointment if idempotencyKey was already processed
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = appointmentRepository.findByIdempotencyKey(idempotencyKey.trim());
            if (existing.isPresent()) {
                Appointment appt = existing.get();
                appt.getReminders().size(); // force load
                log.info("Idempotent request replay for key={}: returning appointment id={}",
                        idempotencyKey, appt.getId());
                return AppointmentResponse.from(appt);
            }
        }

        // Validate contact info
        if (!request.hasValidContact()) {
            throw new InvalidAppointmentException(
                    "At least one contact method (email or phone) must be provided");
        }

        // Validate scheduled time is in the future
        Instant now = Instant.now();
        if (!request.scheduledAt().isAfter(now)) {
            throw new InvalidAppointmentException(
                    "Appointment scheduled time must be in the future");
        }

        // Validate dealership exists
        Dealership dealership = dealershipRepository.findById(request.dealershipId())
                .orElseThrow(() -> new DealershipNotFoundException(request.dealershipId()));

        // Validate no duplicate active booking exists for same customer, dealership, and time
        if (appointmentRepository.existsDuplicateBooking(
                request.dealershipId(), request.scheduledAt(),
                request.customerEmail(), request.customerPhone(),
                AppointmentStatus.SCHEDULED)) {
            throw new DuplicateAppointmentException(
                    "An active appointment already exists for this customer at the specified dealership and time");
        }

        // Build appointment entity
        Appointment appointment = Appointment.builder()
                .dealership(dealership)
                .customerName(request.customerName())
                .customerEmail(request.customerEmail())
                .customerPhone(request.customerPhone())
                .vehicleInfo(request.vehicleInfo())
                .serviceType(request.serviceType())
                .scheduledAt(request.scheduledAt())
                .idempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null)
                .status(AppointmentStatus.SCHEDULED)
                .build();

        appointment = appointmentRepository.save(appointment);
        log.info("Appointment created: id={}, customer={}, scheduledAt={}, dealership={}",
                appointment.getId(), appointment.getCustomerName(),
                appointment.getScheduledAt(), dealership.getName());

        // Eagerly create applicable reminder records using batch save
        List<Reminder> reminders = createRemindersForAppointment(appointment, now);
        appointment.getReminders().addAll(reminders);

        log.info("Created {} reminder(s) for appointment id={}",
                reminders.size(), appointment.getId());

        return AppointmentResponse.from(appointment, reminders);
    }

    /**
     * Get appointment by ID with all details including reminders.
     */
    @Transactional(readOnly = true)
    public AppointmentResponse getAppointment(Long id) {
        Appointment appointment = appointmentRepository.findByIdWithDealership(id)
                .orElseThrow(() -> new AppointmentNotFoundException(id));

        // Force-load reminders within the transaction
        appointment.getReminders().size();

        return AppointmentResponse.from(appointment);
    }

    /**
     * Reschedule an appointment to a new date/time.
     * Cancels existing pending reminders and recalculates reminders for the new time.
     */
    @Transactional
    public AppointmentResponse rescheduleAppointment(Long id, RescheduleAppointmentRequest request) {
        Appointment appointment = appointmentRepository.findByIdWithDealership(id)
                .orElseThrow(() -> new AppointmentNotFoundException(id));

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new InvalidAppointmentException("Cannot reschedule a cancelled appointment");
        }
        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new InvalidAppointmentException("Cannot reschedule a completed appointment");
        }

        Instant newScheduledAt = request.newScheduledAt();
        Instant now = Instant.now();
        if (!newScheduledAt.isAfter(now)) {
            throw new InvalidAppointmentException("New appointment scheduled time must be in the future");
        }

        // Check duplicate booking conflict at new time
        if (appointmentRepository.existsDuplicateBooking(
                appointment.getDealership().getId(), newScheduledAt,
                appointment.getCustomerEmail(), appointment.getCustomerPhone(),
                AppointmentStatus.SCHEDULED)) {
            throw new DuplicateAppointmentException(
                    "An active appointment already exists for this customer at the new specified time");
        }

        // Update scheduled time
        appointment.setScheduledAt(newScheduledAt);
        appointment = appointmentRepository.save(appointment);

        // Adjust existing reminders to respect unique constraint (appointment_id, reminder_type)
        Duration timeUntilAppointment = Duration.between(now, newScheduledAt);
        List<Reminder> existingReminders = reminderRepository.findByAppointmentId(id);
        Map<ReminderType, Reminder> reminderMap = existingReminders.stream()
                .collect(Collectors.toMap(Reminder::getReminderType, r -> r, (r1, r2) -> r1));

        List<Reminder> toSave = new ArrayList<>();
        List<Reminder> activeReminders = new ArrayList<>();
        for (ReminderType type : ReminderType.values()) {
            Duration reminderLeadTime = Duration.ofHours(type.getHoursBefore());
            if (timeUntilAppointment.compareTo(reminderLeadTime) > 0) {
                Instant reminderTime = newScheduledAt.minus(reminderLeadTime);
                Reminder reminder = reminderMap.get(type);
                if (reminder != null) {
                    reminder.setScheduledAt(reminderTime);
                    reminder.setStatus(ReminderStatus.PENDING);
                    reminder.setSentAt(null);
                    reminder.setRetryCount(0);
                    reminder.setErrorMessage(null);
                    toSave.add(reminder);
                    activeReminders.add(reminder);
                } else {
                    Reminder newRem = Reminder.builder()
                            .appointment(appointment)
                            .reminderType(type)
                            .status(ReminderStatus.PENDING)
                            .scheduledAt(reminderTime)
                            .build();
                    toSave.add(newRem);
                    activeReminders.add(newRem);
                }
            } else {
                Reminder reminder = reminderMap.get(type);
                if (reminder != null && reminder.getStatus() == ReminderStatus.PENDING) {
                    reminder.setStatus(ReminderStatus.CANCELLED);
                    toSave.add(reminder);
                }
            }
        }
        // Batch save all reminder updates
        reminderRepository.saveAll(toSave);

        log.info("Appointment id={} rescheduled to {}. Updated {} reminder(s)",
                id, newScheduledAt, activeReminders.size());

        return AppointmentResponse.from(appointment, activeReminders);
    }

    /**
     * Cancel an appointment and all its pending reminders.
     */
    @Transactional
    public AppointmentResponse cancelAppointment(Long id) {
        Appointment appointment = appointmentRepository.findByIdWithDealership(id)
                .orElseThrow(() -> new AppointmentNotFoundException(id));

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new InvalidAppointmentException("Appointment is already cancelled");
        }

        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new InvalidAppointmentException("Cannot cancel a completed appointment");
        }

        // Cancel the appointment
        appointment.setStatus(AppointmentStatus.CANCELLED);

        // Cancel all pending reminders
        List<Reminder> pendingReminders = reminderRepository
                .findByAppointmentIdAndStatus(id, ReminderStatus.PENDING);
        for (Reminder reminder : pendingReminders) {
            reminder.setStatus(ReminderStatus.CANCELLED);
            log.info("Cancelled {} reminder for appointment id={}",
                    reminder.getReminderType(), id);
        }
        reminderRepository.saveAll(pendingReminders);

        appointmentRepository.save(appointment);
        log.info("Appointment cancelled: id={}", id);

        // Load all reminders for response
        List<Reminder> allReminders = reminderRepository.findByAppointmentId(id);
        return AppointmentResponse.from(appointment, allReminders);
    }

    /**
     * List all appointments, paginated.
     */
    @Transactional(readOnly = true)
    public Page<AppointmentResponse> listAppointments(Pageable pageable) {
        return appointmentRepository.findAll(pageable)
                .map(AppointmentResponse::fromWithoutReminders);
    }

    /**
     * List appointments for a specific dealership, paginated.
     */
    @Transactional(readOnly = true)
    public Page<AppointmentResponse> listAppointmentsByDealership(Long dealershipId, Pageable pageable) {
        if (!dealershipRepository.existsById(dealershipId)) {
            throw new DealershipNotFoundException(dealershipId);
        }
        return appointmentRepository.findByDealershipId(dealershipId, pageable)
                .map(AppointmentResponse::fromWithoutReminders);
    }

    /**
     * Get reminders for a specific appointment.
     */
    @Transactional(readOnly = true)
    public List<ReminderResponse> getReminders(Long appointmentId) {
        if (!appointmentRepository.existsById(appointmentId)) {
            throw new AppointmentNotFoundException(appointmentId);
        }
        return reminderRepository.findByAppointmentId(appointmentId)
                .stream()
                .map(ReminderResponse::from)
                .toList();
    }

    // ==================== Private Helpers ====================

    /**
     * Create reminder records for an appointment based on how far away it is.
     * Uses batch saveAll for reduced DB roundtrips.
     *
     * Corner cases handled:
     * - Appointment > 24h away: create both 24h and 2h reminders
     * - Appointment 2-24h away: create only 2h reminder (24h window has passed)
     * - Appointment < 2h away: no reminders created (both windows have passed)
     */
    private List<Reminder> createRemindersForAppointment(Appointment appointment, Instant now) {
        List<Reminder> reminders = new ArrayList<>();
        Duration timeUntilAppointment = Duration.between(now, appointment.getScheduledAt());

        for (ReminderType type : ReminderType.values()) {
            Duration reminderLeadTime = Duration.ofHours(type.getHoursBefore());

            if (timeUntilAppointment.compareTo(reminderLeadTime) > 0) {
                Instant reminderTime = appointment.getScheduledAt().minus(reminderLeadTime);

                Reminder reminder = Reminder.builder()
                        .appointment(appointment)
                        .reminderType(type)
                        .status(ReminderStatus.PENDING)
                        .scheduledAt(reminderTime)
                        .build();

                reminders.add(reminder);

                log.info("Scheduled {} reminder for appointment id={} at {}",
                        type, appointment.getId(), reminderTime);
            } else {
                log.info("Skipping {} reminder for appointment id={} - " +
                                "appointment is only {} hours away (need > {} hours)",
                        type, appointment.getId(),
                        timeUntilAppointment.toHours(), type.getHoursBefore());
            }
        }

        // Batch save all reminders at once instead of individual saves
        if (!reminders.isEmpty()) {
            reminders = reminderRepository.saveAll(reminders);
        }

        return reminders;
    }
}
