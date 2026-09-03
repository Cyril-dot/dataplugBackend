package com.databundleHum.OnetBundleHub.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A single result-checker credential (serial + PIN) held in admin-managed
 * inventory, waiting to be handed out to a customer.
 *
 * ── Why this exists ─────────────────────────────────────────────────────
 * Checker purchase used to call DataBossHub live, at the moment a customer
 * paid (see CheckerService's old purchaseFromDataBossHub()). That's being
 * replaced with a stock model: the Super Admin pre-acquires a batch of
 * checker codes — either by pasting them in manually, or via one click
 * against DataBossHub or Big Dreams Data (see AdminCheckerStockController)
 * — and customer purchases now just claim the next unused row here.
 * No live upstream call happens at customer-purchase time any more.
 *
 * ── Concurrency ──────────────────────────────────────────────────────────
 * Two customers buying the same exam type at the same moment must never be
 * handed the same code. CheckerService.provisionFromStock() claims a row
 * with a pessimistic row lock (see CheckerStockRepository.findFirstAvailable)
 * so this is safe under concurrent purchases without needing a separate
 * distributed lock.
 */
@Entity
@Table(name = "checker_stock")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckerStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "exam_type", nullable = false, length = 20)
    private CheckerPricing.ExamType examType;

    @Column(name = "serial", nullable = false, length = 100)
    private String serial;

    @Column(name = "pin", nullable = false, length = 100)
    private String pin;

    /** Free text — format varies by source and isn't always provided. */
    @Column(name = "exam_date", length = 100)
    private String examDate;

    @Column(name = "results_link", columnDefinition = "TEXT")
    private String resultsLink;

    /** Where this code came from — for admin reporting / reconciliation. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private StockSource source;

    // ── Consumption state ────────────────────────────────────────────────────

    @Column(name = "used", nullable = false)
    @Builder.Default
    private boolean used = false;

    /** Set the moment this row is claimed by a purchase — never before. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** The order this code was handed out to, once claimed. */
    @Column(name = "checker_order_id")
    private Long checkerOrderId;

    // ── Audit ─────────────────────────────────────────────────────────────────

    /** Which admin added this row — for accountability on manual entries. */
    @Column(name = "added_by_admin_id")
    private java.util.UUID addedByAdminId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum StockSource {
        MANUAL, DATABOSSHUB, BIGDREAMS
    }
}
