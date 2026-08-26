package com.databundleHum.OnetBundleHub.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Represents a single result-checker (BECE/WASSCE/etc.) purchase — placed by
 * a guest, a regular user, or a reseller's storefront customer.
 *
 * Unlike Order (data bundles), this is a FRESH entity with no legacy Paystack
 * naming baggage — gatewayRef / KORAPAY are named correctly from day one.
 *
 * ── Why this doesn't need a delivery poller ──────────────────────────────
 * DataPrimo's data-bundle purchase is fire-and-forget (accepted now,
 * delivered later — hence DataPrimoService.checkDeliveryStatus()).
 * DataBossHub's checker purchase (POST /checker/buy/{id}) is SYNCHRONOUS —
 * it returns the serial/PIN/exam date immediately in the same response, or
 * fails immediately. So a CheckerOrder goes PENDING → COMPLETED/FAILED in
 * one request; there is no intermediate "accepted but not yet delivered"
 * state to poll for.
 *
 * ── Slot contention ───────────────────────────────────────────────────────
 * DataBossHub's checkers are finite numbered slots (GET /checker/slots lists
 * available ones by id; POST /checker/buy/{id} claims a specific one). Two
 * concurrent purchases could race for the same slot id — CheckerService
 * retries against a freshly re-fetched slot list on contention, so the slot
 * id actually purchased may differ from the first one attempted. Only the
 * FINAL successful dataBossHubSlotId is persisted.
 */
@Entity
@Table(
        name = "checker_orders",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_checker_orders_idempotency",
                columnNames = {"user_id", "phone_number", "exam_type", "idempotency_key"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL for guest orders. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Recipient phone number — where the SMS with serial/PIN is sent. */
    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "exam_type", nullable = false, length = 20)
    private CheckerPricing.ExamType examType;

    /** What the customer actually paid (GHS). */
    @Column(name = "price_ghc", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceGhc;

    // ── Payment ───────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    /** Korapay reference — null for wallet-paid orders. */
    @Column(name = "gateway_ref", unique = true, length = 100)
    private String gatewayRef;

    // ── DataBossHub provider fields ───────────────────────────────────────────

    /** The specific slot id ultimately purchased from DataBossHub. */
    @Column(name = "databosshub_slot_id", length = 100)
    private String dataBossHubSlotId;

    /** Delivered credential — the checker serial number. */
    @Column(name = "serial", length = 100)
    private String serial;

    /** Delivered credential — the checker PIN. */
    @Column(name = "pin", length = 100)
    private String pin;

    /** Exam date as returned by DataBossHub — kept as free text, format unconfirmed. */
    @Column(name = "exam_date", length = 100)
    private String examDate;

    /** Results-checking URL, if DataBossHub returns one. */
    @Column(name = "results_link", columnDefinition = "TEXT")
    private String resultsLink;

    // ── Status ────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CheckerOrderStatus status = CheckerOrderStatus.PENDING;

    /** Populated only when status = FAILED, for support/refund investigation. */
    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    // ── Origin flags ──────────────────────────────────────────────────────────

    @Column(name = "is_guest", nullable = false)
    @Builder.Default
    private boolean guest = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reseller_profile_id")
    private ResellerProfile resellerProfile;

    @Column(name = "storefront_order", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean storefrontOrder = false;

    // ── Idempotency ───────────────────────────────────────────────────────────

    /** Same 30-second-bucket duplicate guard pattern as Order.idempotencyKey. */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

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

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum PaymentMethod {
        WALLET, KORAPAY
    }

    public enum CheckerOrderStatus {
        PENDING, VERIFIED, COMPLETED, FAILED
    }
}
