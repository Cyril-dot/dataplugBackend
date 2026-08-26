package com.databundleHum.OnetBundleHub.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Platform-wide pricing for a result-checker exam type (BECE, WASSCE, etc.).
 * Mirrors PlatformSettings' role for data bundles: one row per exam type,
 * set by the Super Admin, and the single source of truth for reseller
 * wholesale cost.
 *
 * dataBossHubCategory is the exact category string DataBossHub's own API
 * expects on GET /checker/slots?category=... — kept separate from the
 * examType enum name in case DataBossHub's casing/spelling ever differs
 * (e.g. enum WASSCE vs a DataBossHub category string "WASSCE " with a
 * trailing space, or a different exam added later that doesn't map 1:1).
 */
@Entity
@Table(name = "checker_pricing")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckerPricing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "exam_type", nullable = false, unique = true, length = 20)
    private ExamType examType;

    /** Price charged to regular users / guests (GHS). */
    @Column(name = "public_price_ghc", nullable = false, precision = 10, scale = 2)
    private BigDecimal publicPriceGhc;

    /** Wholesale price charged to approved resellers (GHS). */
    @Column(name = "reseller_price_ghc", nullable = false, precision = 10, scale = 2)
    private BigDecimal resellerPriceGhc;

    /**
     * The exact category string to pass to DataBossHub's
     * GET /checker/slots?category=... — verify against a real response
     * before relying on this; DataBossHub's documented example uses
     * "BECE" but exact casing/spelling for other exam types is unconfirmed.
     */
    @Column(name = "databosshub_category", length = 50)
    private String dataBossHubCategory;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

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

    public enum ExamType {
        BECE, WASSCE
    }
}
