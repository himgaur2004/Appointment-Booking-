package com.appointment.controller;

import com.appointment.dto.StatsResponse;
import com.appointment.enums.AppointmentStatus;
import com.appointment.enums.ReminderStatus;
import com.appointment.repository.AppointmentRepository;
import com.appointment.repository.DealershipRepository;
import com.appointment.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller providing operational metrics and statistics for monitoring.
 */
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
@Slf4j
public class StatsController {

    private final AppointmentRepository appointmentRepository;
    private final ReminderRepository reminderRepository;
    private final DealershipRepository dealershipRepository;

    @GetMapping
    public ResponseEntity<StatsResponse> getStats() {
        long totalAppointments = appointmentRepository.count();
        long totalReminders = reminderRepository.count();
        long totalDealerships = dealershipRepository.count();

        Map<String, Long> apptByStatus = new LinkedHashMap<>();
        for (AppointmentStatus status : AppointmentStatus.values()) {
            apptByStatus.put(status.name(), 0L);
        }
        for (Object[] row : appointmentRepository.countAppointmentsByStatus()) {
            if (row[0] != null) {
                apptByStatus.put(((AppointmentStatus) row[0]).name(), (Long) row[1]);
            }
        }

        Map<String, Long> remByStatus = new LinkedHashMap<>();
        for (ReminderStatus status : ReminderStatus.values()) {
            remByStatus.put(status.name(), 0L);
        }
        for (Object[] row : reminderRepository.countRemindersByStatus()) {
            if (row[0] != null) {
                remByStatus.put(((ReminderStatus) row[0]).name(), (Long) row[1]);
            }
        }

        StatsResponse response = new StatsResponse(
                totalAppointments,
                apptByStatus,
                totalReminders,
                remByStatus,
                totalDealerships,
                Instant.now()
        );

        return ResponseEntity.ok(response);
    }
}
