package com.databundleHum.OnetBundleHub.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "paystack_withdrawals", indexes = {
        @Index(name = "idx_paystack_withdrawal_reference", columnList = "reference", unique = true),
        @Index(name = "idx_paystack_withdrawal_status_updated", columnList = "status,updated_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaystackWithdrawal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String reference;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amountGhc;

    @Column(nullable = false)
    private long amountPesewas;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_type", nullable = false, length = 20)
    private PayoutType payoutType;

    @Column(name = "account_name", nullable = false, length = 160)
    private String accountName;

    @Column(name = "account_number", nullable = false, length = 40)
    private String accountNumber;

    @Column(name = "bank_code", nullable = false, length = 40)
    private String bankCode;

    @Column(name = "recipient_code", length = 100)
    private String recipientCode;

    @Column(length = 160)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WithdrawalStatus status = WithdrawalStatus.PENDING;

    @Column(name = "transfer_code", length = 100)
    private String transferCode;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Version
    private Long version;

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum PayoutType { MOBILE_MONEY, BANK }
    public enum WithdrawalStatus { PENDING, PROCESSING, SUCCESS, FAILED, REVERSED }
}
