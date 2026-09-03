package com.databundleHum.OnetBundleHub.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Platform user entity.
 *
 * New fields added for the affiliate and referral system (architecture §5):
 *
 *   is_affiliate             — true when the user has activated the affiliate programme.
 *   affiliate_code           — 8-char alphanumeric code, generated once on activation,
 *                              retained (but effectively inactive) after deactivation.
 *   referred_by_affiliate_id — FK to the affiliate whose link brought this user in.
 *   referred_by_reseller_id  — FK to the reseller whose /ref/{slug} link brought this
 *                              user in (for sub-customer dashboard tracking).
 *   affiliate_earnings_ghc   — running balance of commission earned by this user as an
 *                              affiliate. COMPLETELY SEPARATE from wallet_balance — this
 *                              is payout-only money, never spendable on bundle purchases
 *                              and never touched by wallet top-ups or debits. Only
 *                              AffiliateCommissionService and AffiliateService.requestPayout()
 *                              are allowed to mutate it.
 *
 * Flyway migration: V_next__add_affiliate_system.sql
 *
 *   ALTER TABLE users
 *     ADD COLUMN IF NOT EXISTS is_affiliate              BOOLEAN     NOT NULL DEFAULT FALSE,
 *     ADD COLUMN IF NOT EXISTS affiliate_code            VARCHAR(16) UNIQUE,
 *     ADD COLUMN IF NOT EXISTS referred_by_affiliate_id  UUID        REFERENCES users(id),
 *     ADD COLUMN IF NOT EXISTS referred_by_reseller_id   UUID        REFERENCES users(id);
 *
 * Flyway migration: V_next__add_affiliate_earnings.sql
 *
 *   ALTER TABLE users
 *     ADD COLUMN IF NOT EXISTS affiliate_earnings_ghc    NUMERIC(12,2) NOT NULL DEFAULT 0;
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(nullable = false, unique = true, length = 200)
    private String email;

    @Column(nullable = false, unique = true, length = 30)
    private String phone;

    @Column(name = "password_hash", nullable = false)
    // ✅ SECURITY FIX: without this, any endpoint that ever accidentally
    // serializes a User entity directly (instead of a DTO) leaks the bcrypt
    // hash in the JSON response — found via ResellerCheckerPricingController
    // returning raw CheckerResellerPricing entities with a loaded User
    // nested inside. This is defense-in-depth: the specific endpoint is
    // also fixed to use a proper DTO, but this protects every other
    // current and future spot too.
    @JsonIgnore
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;

    @Column(name = "wallet_balance", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal walletBalance = BigDecimal.ZERO;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    // ── Affiliate fields ──────────────────────────────────────────────────────

    /**
     * Whether this user has activated the affiliate programme.
     * Toggled by AffiliateService.activate() / deactivate().
     * Setting this to false retains the affiliateCode in the DB
     * (existing referral links should not 404 — they just stop attributing).
     */
    @Column(name = "is_affiliate", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean affiliate = false;

    /**
     * 8-character alphanumeric affiliate code, e.g. "A3KP9WZQ".
     * Generated once by AffiliateService.activate() and never changed.
     * Used to build the referral URL: /a/{affiliateCode}
     *
     * Retained in the DB even after deactivation so that
     * /a/{code} can return a graceful "inactive" message rather than 404.
     */
    @Column(name = "affiliate_code", length = 16, unique = true)
    private String affiliateCode;

    /**
     * Running balance of commission earned as an affiliate. This is a
     * SEPARATE pot of money from walletBalance:
     *   - Credited only by AffiliateCommissionService.processCommission()
     *   - Debited only by AffiliateCommissionService.reverseCommission()
     *     and AffiliateService.requestPayout()
     *   - Never spendable on bundle purchases, never affected by wallet
     *     top-ups or wallet debits.
     * Payouts are cash-out requests against THIS balance, not walletBalance.
     */
    @Column(name = "affiliate_earnings_ghc", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal affiliateEarningsGhc = BigDecimal.ZERO;

    /**
     * FK to the affiliate user who referred this user at signup.
     * Set in AuthService.register() by reading the ref_affiliate cookie.
     * Null if the user signed up without an affiliate referral link.
     * Self-referral is blocked: this field is never set to the user's own ID.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referred_by_affiliate_id")
    private User referredByAffiliate;

    /**
     * FK to the reseller user whose /ref/{slug} link brought this user in.
     * Set in AuthService.register() by reading the ref_reseller_id cookie.
     * Used for the reseller's sub-customer dashboard only; does not
     * affect commission logic (that is affiliate-only).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referred_by_reseller_id")
    private User referredByReseller;

    // ── Audit timestamps ──────────────────────────────────────────────────────

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

    // ── Enum ──────────────────────────────────────────────────────────────────

    public enum Role {
        USER, RESELLER, SUPER_ADMIN
    }
}