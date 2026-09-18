package com.appointment.controller;

import com.appointment.dto.AppointmentResponse;
import com.appointment.dto.CreateAppointmentRequest;
import com.appointment.dto.RescheduleAppointmentRequest;
import com.appointment.dto.ReminderResponse;
import com.appointment.service.AppointmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for managing vehicle service appointments.
 */
@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
@Slf4j
public class AppointmentController {

    private final AppointmentService appointmentService;

    /**
     * POST /api/v1/appointments
     * Create a new appointment with scheduled reminders.
     */
    @PostMapping
    public ResponseEntity<AppointmentResponse> createAppointment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateAppointmentRequest request) {
        log.info("POST /appointments - customer={}, scheduledAt={}, dealership={}, idempotencyKey={}",
                request.customerName(), request.scheduledAt(), request.dealershipId(), idempotencyKey);

        AppointmentResponse response = appointmentService.createAppointment(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * PATCH /api/v1/appointments/{id}/reschedule
     * Reschedule an appointment to a new time and adjust pending reminders.
     */
    @PatchMapping("/{id}/reschedule")
    public ResponseEntity<AppointmentResponse> rescheduleAppointment(
            @PathVariable Long id,
            @Valid @RequestBody RescheduleAppointmentRequest request) {
        log.info("PATCH /appointments/{}/reschedule - newScheduledAt={}", id, request.newScheduledAt());
        return ResponseEntity.ok(appointmentService.rescheduleAppointment(id, request));
    }

    /**
     * GET /api/v1/appointments/{id}
     * Get appointment details with reminder statuses.
     */
    @GetMapping("/{id}")
    public ResponseEntity<AppointmentResponse> getAppointment(@PathVariable Long id) {
        log.info("GET /appointments/{}", id);
        return ResponseEntity.ok(appointmentService.getAppointment(id));
    }

    /**
     * PUT /api/v1/appointments/{id}/cancel
     * Cancel an appointment and its pending reminders.
     */
    @PutMapping("/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancelAppointment(@PathVariable Long id) {
        log.info("PUT /appointments/{}/cancel", id);
        return ResponseEntity.ok(appointmentService.cancelAppointment(id));
    }

    /**
     * GET /api/v1/appointments
     * List all appointments, paginated.
     * Optional query param: dealershipId to filter by dealership.
     */
    @GetMapping
    public ResponseEntity<Page<AppointmentResponse>> listAppointments(
            @RequestParam(required = false) Long dealershipId,
            @PageableDefault(size = 20, sort = "scheduledAt", direction = Sort.Direction.ASC) Pageable pageable) {
        log.info("GET /appointments - dealershipId={}, page={}", dealershipId, pageable);

        Page<AppointmentResponse> page;
        if (dealershipId != null) {
            page = appointmentService.listAppointmentsByDealership(dealershipId, pageable);
        } else {
            page = appointmentService.listAppointments(pageable);
        }

        return ResponseEntity.ok(page);
    }

    /**
     * GET /api/v1/appointments/{id}/reminders
     * View all reminders for an appointment.
     */
    @GetMapping("/{id}/reminders")
    public ResponseEntity<List<ReminderResponse>> getReminders(@PathVariable Long id) {
        log.info("GET /appointments/{}/reminders", id);
        return ResponseEntity.ok(appointmentService.getReminders(id));
    }
}
