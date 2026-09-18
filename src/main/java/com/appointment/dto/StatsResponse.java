package com.appointment.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Operational statistics and system health metrics.
 */
public record StatsResponse(
        long totalAppointments,
        Map<String, Long> appointmentsByStatus,
        long totalReminders,
        Map<String, Long> remindersByStatus,
        long totalDealerships,
        Instant timestamp
) {}
