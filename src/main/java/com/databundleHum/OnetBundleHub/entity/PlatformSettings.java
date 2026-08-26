package com.databundleHum.OnetBundleHub.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Stores per-network, per-capacity pricing rows set by the Super Admin.
 * Also acts as the single source of truth for reseller cost prices.
 *
 * One row = one (network, capacity_gb) combination.
 * setting_key is auto-derived as "{NETWORK}_{GB}GB" — e.g. "MTN_1GB"
 *
 * ── DATAPRIMO INTEGRATION (2026-08-26) ────────────────────────────────────
 * dataprimoProductId / dataprimoNetwork identify this exact bundle in
 * DataPrimo's catalog (GET /catalog). Unlike Big Dreams, DataPrimo has no
 * fixed/predictable network vocabulary that can be hardcoded in Java — the
 * only source of truth is a real catalog response for this account. These
 * two columns must be populated manually (or via an admin tool) by matching
 * this row's network/capacityGb against a real GET /catalog response, before
 * DataPrimoService.purchase(...) can be called for this bundle.
 *
 * NOTE: if a network ever has multiple product tiers at the same capacity
 * (e.g. "MTN" standard vs "MTN Express"), this schema as written can only
 * represent ONE of them per (network, capacity_gb) row — see the
 * product-variant discussion before adding Express-style bundles. A
 * dedicated product_variant column + relaxed unique constraint would be
 * needed to support both tiers as separate purchasable options.
 */
@Entity
@Table(
        name = "platform_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_platform_settings_network_capacity",
                columnNames = {"network", "capacity_gb"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Derived unique key — auto-generated from network + capacityGb.
     * Format: "MTN_1GB", "TELECEL_5GB", etc.
     * Never set this manually — @PrePersist and @PreUpdate handle it.
     */
    @Column(name = "setting_key", nullable = false, unique = true, length = 50)
    private String settingKey;

    /**
     * Network this pricing row applies to.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Network network;

    /**
     * Bundle size in GB this row covers.
     */
    @Column(name = "capacity_gb", nullable = false, precision = 6, scale = 2)
    private BigDecimal capacityGb;

    /**
     * Price charged to regular users / guests (GHS).
     */
    @Column(name = "public_price_ghc", nullable = false, precision = 10, scale = 2)
    private BigDecimal publicPriceGhc;

    /**
     * Wholesale price charged to approved resellers (GHS).
     * Must always be < publicPriceGhc.
     */
    @Column(name = "reseller_price_ghc", nullable = false, precision = 10, scale = 2)
    private BigDecimal resellerPriceGhc;

    /**
     * Whether this bundle is currently purchasable.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * DataPrimo catalog productId for this exact bundle. Nullable so
     * existing rows don't break — but purchase() will fail loudly for any
     * bundle where this hasn't been set. Resolve by matching this row's
     * network/capacityGb against GET /catalog and filling this in manually.
     */
    @Column(name = "dataprimo_product_id", length = 100)
    private String dataprimoProductId;

    /**
     * DataPrimo network string for this bundle (catalog-defined, not
     * necessarily the same string as our internal Network enum name).
     */
    @Column(name = "dataprimo_network", length = 50)
    private String dataprimoNetwork;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    // ── Lifecycle hooks ────────────────────────────────────────────────────────

    @PrePersist
    public void onCreate() {
        deriveSettingKey();
        if (this.createdAt == null) this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        deriveSettingKey();
        this.updatedAt = Instant.now();
    }

    private void deriveSettingKey() {
        if (this.network != null && this.capacityGb != null) {
            // Strip trailing zeros: 1.00 → "1", 1.50 → "1.5"
            String gbStr = this.capacityGb.stripTrailingZeros().toPlainString();
            this.settingKey = this.network.name() + "_" + gbStr + "GB";
        }
    }

    // ── Enums ──────────────────────────────────────────────────────────────────

    public enum Network {
        MTN, TELECEL, AIRTELTIGO
    }
}
