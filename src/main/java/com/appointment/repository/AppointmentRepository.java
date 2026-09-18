package com.appointment.repository;

import com.appointment.entity.Appointment;
import com.appointment.enums.AppointmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    /**
     * Find appointment by ID with dealership eagerly loaded (avoids N+1).
     */
    @Query("SELECT a FROM Appointment a JOIN FETCH a.dealership WHERE a.id = :id")
    Optional<Appointment> findByIdWithDealership(@Param("id") Long id);

    /**
     * Find appointments for a specific dealership, paginated.
     */
    Page<Appointment> findByDealershipId(Long dealershipId, Pageable pageable);

    /**
     * Find all active (SCHEDULED) appointments in a time range.
     * Used to discover appointments needing reminders.
     */
    @Query("SELECT a FROM Appointment a WHERE a.scheduledAt BETWEEN :start AND :end AND a.status = :status")
    List<Appointment> findByScheduledAtBetweenAndStatus(
            @Param("start") Instant start,
            @Param("end") Instant end,
            @Param("status") AppointmentStatus status);

    /**
     * Check if an active appointment already exists for the same dealership,
     * scheduled time, and customer (matching either email or phone).
     */
    @Query("""
        SELECT COUNT(a) > 0 FROM Appointment a 
        WHERE a.dealership.id = :dealershipId 
          AND a.scheduledAt = :scheduledAt 
          AND a.status = :status 
          AND (
              (:email IS NOT NULL AND a.customerEmail = :email) 
              OR (:phone IS NOT NULL AND a.customerPhone = :phone)
          )
    """)
    boolean existsDuplicateBooking(
            @Param("dealershipId") Long dealershipId,
            @Param("scheduledAt") Instant scheduledAt,
            @Param("email") String email,
            @Param("phone") String phone,
            @Param("status") AppointmentStatus status);

    /**
     * Paginated listing of all appointments.
     */
    Page<Appointment> findAll(Pageable pageable);

    /**
     * Find appointment by client-provided Idempotency-Key for safe retries.
     */
    Optional<Appointment> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT a.status, COUNT(a) FROM Appointment a GROUP BY a.status")
    java.util.List<Object[]> countAppointmentsByStatus();
}
