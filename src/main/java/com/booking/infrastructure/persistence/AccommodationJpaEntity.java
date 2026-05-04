package com.booking.infrastructure.persistence;

import com.booking.domain.accommodation.Accommodation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA mapping for {@code accommodation} table (ERD §4.7 / §8 V1__init.sql).
 */
@Entity
@Table(name = "accommodation")
public class AccommodationJpaEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "base_price", nullable = false)
    private BigDecimal basePrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccommodationJpaEntity() {
        // JPA spec — no-arg constructor
    }

    public Accommodation toDomain() {
        return new Accommodation(id, name, basePrice, createdAt);
    }
}
