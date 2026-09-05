package com.databundleHum.OnetBundleHub.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persisted record of a wallet top-up attempt, created at initiation time
 * (before the Korapay charge is even created) so the webhook has something
 * to look up purely by reference.
 *
 * ── Why this exists ─────────────────────────────────────────────────────
 * The webhook used to receive userId/baseAmountGhc via the metadata field
 * we sent when initiating the Korapay charge. Confirmed via live Railway
 * logs: Korapay's actual charge.success webhook payload does NOT echo
 * metadata back at all — "Data block keys: [reference, payment_reference,
 * currency, amount, fee, payment_method, status]", no "metadata" key
 * present. Every wallet top-up (and, before this fix, every checker/bundle
 * order too — see WebhookController) was silently un-routable from the
 * webhook alone.
 *
 * This table removes that dependency: OrderService.initiateTopUp() saves
 * one of these (status=PENDING) right after generating the reference and
 * before calling Korapay at all, so by the time any webhook or manual
 * verify arrives, looking up {@code findByGatewayRef(reference)} reliably
 * returns the userId — no metadata round-trip required.
 */
@Entity
@Table(name = "wallet_topups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletTopUp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gateway_ref", nullable = false, unique = true, length = 100)
    private String gatewayRef;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Amount actually requested by the user, before the processing charge. */
    @Column(name = "base_amount_ghc", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseAmountGhc;

    /** Amount actually charged via Korapay, including the processing charge. */
    @Column(name = "charge_amount_ghc", nullable = false, precision = 12, scale = 2)
    private BigDecimal chargeAmountGhc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public enum Status {
        PENDING, COMPLETED, FAILED
    }
}
