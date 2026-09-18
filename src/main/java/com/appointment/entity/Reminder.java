package com.appointment.entity;

import com.appointment.enums.ReminderStatus;
import com.appointment.enums.ReminderType;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * Represents a scheduled reminder notification for an appointment.
 * The unique constraint on (appointment_id, reminder_type) guarantees
 * that a customer never receives the same reminder twice.
 */
@Entity
@Table(name = "reminders", uniqueConstraints = {
        @UniqueConstraint(name = "uq_appointment_reminder",
                columnNames = {"appointment_id", "reminder_type"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "reminders_seq")
    @SequenceGenerator(name = "reminders_seq", sequenceName = "reminders_id_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    @Enumerated(EnumType.STRING)
    @Column(name = "reminder_type", nullable = false)
    private ReminderType reminderType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReminderStatus status = ReminderStatus.PENDING;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
