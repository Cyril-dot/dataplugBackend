package com.databundleHum.OnetBundleHub.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Reseller's custom selling price for a checker exam type, sold through
 * their public storefront. Mirrors ResellerPricing's role for data bundles.
 */
@Entity
@Table(
        name = "checker_reseller_pricing",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_checker_reseller_pricing_reseller_examtype",
                columnNames = {"reseller_id", "exam_type"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckerResellerPricing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reseller_id", nullable = false)
    private User reseller;

    @Enumerated(EnumType.STRING)
    @Column(name = "exam_type", nullable = false, length = 20)
    private CheckerPricing.ExamType examType;

    /** Price the reseller charges their own customers (must be >= reseller cost price). */
    @Column(name = "selling_price_ghc", nullable = false, precision = 10, scale = 2)
    private BigDecimal sellingPriceGhc;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
