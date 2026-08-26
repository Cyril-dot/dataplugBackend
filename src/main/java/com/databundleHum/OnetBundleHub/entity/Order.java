package com.databundleHum.OnetBundleHub.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Represents a single data-bundle purchase — placed by a guest, a regular user,
 * or a reseller. Guest orders have user_id = NULL.
 *
 * ── Storefront order tagging ──────────────────────────────────────────────────
 *
 * Orders that originate from a reseller's public storefront are tagged with:
 *   - resellerProfile → the ResellerProfile that owns the store
 *   - storefrontOrder  → true
 *   - orderedByRole    → RESELLER
 *   - sellingPriceGhc  → the reseller's custom price (what the customer paid)
 *   - costPriceGhc     → the platform wholesale price (what the platform charges)
 *
 * Orders placed by resellers directly through their own wallet (not a storefront
 * customer flow) have storefrontOrder = false.
 *
 * ── Affiliate commission gating ───────────────────────────────────────────────
 *
 * AffiliateCommissionService.processCommission() skips any order where
 * storefrontOrder = true (those accrue as reseller margin, not affiliate commission).
 *
 * ── Double-order protection (Layer 2) ────────────────────────────────────────
 *
 * The idempotency_key column, combined with the unique index
 * uq_orders_idempotency, prevents two concurrent requests from both
 * inserting an order in the same 30-second bucket.
 *
 *   Required Flyway migration (run once — included in V_next__add_reseller_affiliate_system.sql):
 *
 *     ALTER TABLE orders
 *       ADD COLUMN IF NOT EXISTS idempotency_key       VARCHAR(128);
 *     ALTER TABLE orders
 *       ADD COLUMN IF NOT EXISTS reseller_profile_id   BIGINT REFERENCES reseller_profiles(id);
 *     ALTER TABLE orders
 *       ADD COLUMN IF NOT EXISTS storefront_order       BOOLEAN NOT NULL DEFAULT FALSE;
 *
 *     CREATE UNIQUE INDEX IF NOT EXISTS uq_orders_idempotency
 *       ON orders (user_id, phone_number, network, capacity_gb, idempotency_key)
 *       WHERE idempotency_key IS NOT NULL;
 *
 *     CREATE INDEX IF NOT EXISTS idx_orders_reseller_profile
 *       ON orders (reseller_profile_id)
 *       WHERE reseller_profile_id IS NOT NULL;
 *
 *   Guest orders leave idempotency_key NULL (excluded from the unique index by the
 *   WHERE clause) because they have no user_id to bucket on.
 */
@Entity
@Table(
        name = "orders",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_orders_idempotency",
                columnNames = { "user_id", "phone_number", "network", "capacity_gb", "idempotency_key" }
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Buyer identity ────────────────────────────────────────────────────────

    /** NULL for guest orders. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // ── Bundle details ────────────────────────────────────────────────────────

    /** Beneficiary phone number — the number that receives the data. */
    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlatformSettings.Network network;

    @Column(name = "capacity_gb", nullable = false, precision = 6, scale = 2)
    private BigDecimal capacityGb;

    // ── Pricing ───────────────────────────────────────────────────────────────

    /**
     * The wholesale price charged by the platform for this bundle (GHS).
     * This is what is deducted from the reseller's wallet or charged via Paystack
     * to fulfil the order through Big Dreams Data.
     *
     * For regular user and guest orders: equals publicPriceGhc.
     * For reseller direct orders: equals resellerPriceGhc.
     * For storefront orders: equals resellerPriceGhc (the platform's cost to fill it).
     */
    @Column(name = "cost_price_ghc", nullable = false, precision = 10, scale = 2)
    private BigDecimal costPriceGhc;

    /**
     * The price the end customer actually paid (GHS).
     *
     * For regular user and guest orders: equals publicPriceGhc (same as cost).
     * For reseller direct orders: the reseller's own selling price (stored for P&L).
     * For storefront orders: the reseller's custom selling price charged to their customer.
     *   → The margin (sellingPriceGhc − costPriceGhc) is the reseller's profit per order.
     *
     * Affiliate commission is calculated on sellingPriceGhc × 2%.
     */
    @Column(name = "selling_price_ghc", nullable = false, precision = 10, scale = 2)
    private BigDecimal sellingPriceGhc;

    // ── Payment ───────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    /** Paystack reference — null for wallet-paid orders. */
    @Column(name = "paystack_ref", unique = true, length = 100)
    private String paystackRef;

    // ── Provider (Big Dreams Data) ────────────────────────────────────────────

    /**
     * Numeric transaction ID returned by the Big Dreams Data API in the
     * place_order response data.transaction_id field.
     * Used for reconciliation in the Big Dreams dashboard.
     */
    @Column(name = "dbh_purchase_id")
    private Long dbhPurchaseId;

    /**
     * Reference string returned by the Big Dreams Data API in the
     * place_order response data.reference field (e.g. "SPARK_REF_123").
     */
    @Column(name = "dbh_reference", length = 100)
    private String dbhReference;

    // ── Status ────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    // ── Order origin flags ────────────────────────────────────────────────────

    /**
     * True when the order was placed without a logged-in user account.
     * Guest orders have user = null and are always paid via Paystack.
     */
    @Column(name = "is_guest", nullable = false)
    @Builder.Default
    private boolean guest = false;

    /**
     * Distinguishes user-rate vs reseller-rate purchases for price routing
     * and analytics. RESELLER orders use resellerPriceGhc as costPriceGhc.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ordered_by_role", nullable = false, length = 20)
    private OrderedByRole orderedByRole;

    // ── Storefront fields ─────────────────────────────────────────────────────

    /**
     * The reseller's profile whose public storefront generated this order.
     *
     * Non-null only when storefrontOrder = true.
     * Used by:
     *   - ResellerStorefrontService.updateResellerStats() to post revenue/profit
     *     to the correct ResellerProfile after delivery.
     *   - AdminService and reporting queries to attribute revenue to a store.
     *
     * Flyway: ADD COLUMN reseller_profile_id BIGINT REFERENCES reseller_profiles(id)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reseller_profile_id")
    private ResellerProfile resellerProfile;

    /**
     * True when this order was placed by a customer through a reseller's public
     * storefront (/store/{slug}), whether the customer paid via Paystack or wallet.
     *
     * When true:
     *   - AffiliateCommissionService skips this order (margin goes to reseller, not affiliate).
     *   - After successful delivery, ResellerStorefrontService.updateResellerStats()
     *     increments the reseller's totalRevenue / totalCost / totalProfit aggregates.
     *   - sellingPriceGhc = reseller's custom price; costPriceGhc = platform wholesale price.
     *
     * When false (direct user purchase or reseller's own wallet top-up purchase):
     *   - Affiliate commission is evaluated normally via AffiliateCommissionService.
     *
     * Flyway: ADD COLUMN storefront_order BOOLEAN NOT NULL DEFAULT FALSE
     */
    @Column(name = "storefront_order", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean storefrontOrder = false;

    // ── Idempotency ───────────────────────────────────────────────────────────

    /**
     * Layer-2 duplicate guard — set by OrderService.buildIdempotencyKey().
     *
     * Value: "{userId}:{phone}:{network}:{capacityGb}:{epochSeconds/30}"
     * Two requests in the same 30-second window produce the same key.
     * The unique index uq_orders_idempotency causes the second INSERT to throw
     * DataIntegrityViolationException, which OrderService catches and converts
     * to DuplicateOrderException (HTTP 409). The @Transactional rollback on the
     * losing thread automatically reverses any wallet debit that already ran.
     *
     * NULL for guest orders (no user_id to bucket on).
     */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    // ── Audit timestamps ──────────────────────────────────────────────────────

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();


    @Column(name = "dataprimo_order_id", length = 100)
    private String dataprimoOrderId;

    @Column(name = "dataprimo_reference", length = 100)
    private String dataprimoReference;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum PaymentMethod {
        WALLET, PAYSTACK
    }

    public enum OrderStatus {
        PENDING, VERIFIED, DELIVERED, FAILED, COMPLETED
    }

    public enum OrderedByRole {
        USER, RESELLER
    }
}