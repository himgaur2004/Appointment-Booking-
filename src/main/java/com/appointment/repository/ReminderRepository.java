package com.appointment.repository;

import com.appointment.entity.Reminder;
import com.appointment.enums.ReminderStatus;
import com.appointment.enums.ReminderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    /**
     * Find all due reminders (PENDING and scheduled_at <= now) in batch.
     * Uses PESSIMISTIC_WRITE to lock rows, preventing double-processing
     * in multi-instance deployments.
     *
     * Note: FOR UPDATE SKIP LOCKED is PostgreSQL-specific. For H2 tests,
     * we use the JPQL version without SKIP LOCKED.
     */
    /**
     * PostgreSQL FOR UPDATE SKIP LOCKED query for concurrent multi-instance scheduler workers.
     * Locks matching rows and skips rows locked by other instances to eliminate race conditions.
     */
    @Query(value = "SELECT r.id FROM reminders r " +
                   "WHERE r.status = :status AND r.scheduled_at <= :now " +
                   "ORDER BY r.scheduled_at ASC " +
                   "LIMIT :batchSize " +
                   "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Long> findDueReminderIdsWithLock(
            @Param("status") String status,
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    /**
     * Fetch locked reminders eagerly with appointment and dealership.
     */
    @Query("SELECT r FROM Reminder r JOIN FETCH r.appointment a JOIN FETCH a.dealership WHERE r.id IN :ids ORDER BY r.scheduledAt ASC")
    List<Reminder> findAllByIdWithDetails(@Param("ids") List<Long> ids);

    @Query("SELECT r FROM Reminder r JOIN FETCH r.appointment a JOIN FETCH a.dealership " +
           "WHERE r.status = :status AND r.scheduledAt <= :now " +
           "ORDER BY r.scheduledAt ASC")
    List<Reminder> findDueReminders(@Param("status") ReminderStatus status, @Param("now") Instant now);

    /**
     * Check if a reminder already exists for a specific appointment and type.
     * Used for idempotency checks at the application level (in addition to DB constraint).
     */
    Optional<Reminder> findByAppointmentIdAndReminderType(Long appointmentId, ReminderType reminderType);

    /**
     * Find all reminders for a specific appointment.
     */
    List<Reminder> findByAppointmentId(Long appointmentId);

    /**
     * Find failed reminders eligible for retry.
     */
    @Query("SELECT r FROM Reminder r JOIN FETCH r.appointment a JOIN FETCH a.dealership " +
           "WHERE r.status = 'FAILED' AND r.retryCount < :maxRetries " +
           "AND r.scheduledAt <= :now " +
           "ORDER BY r.scheduledAt ASC")
    List<Reminder> findRetryableReminders(@Param("maxRetries") int maxRetries, @Param("now") Instant now);

    /**
     * Find all reminders for an appointment with a given status.
     */
    List<Reminder> findByAppointmentIdAndStatus(Long appointmentId, ReminderStatus status);

    @Query("SELECT r.status, COUNT(r) FROM Reminder r GROUP BY r.status")
    List<Object[]> countRemindersByStatus();
}
