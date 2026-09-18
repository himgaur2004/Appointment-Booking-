package com.appointment.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * Represents a vehicle service dealership.
 */
@Entity
@Table(name = "dealerships")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dealership {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "dealerships_seq")
    @SequenceGenerator(name = "dealerships_seq", sequenceName = "dealerships_id_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String address;

    @Column(nullable = false)
    private String timezone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
