package com.databundleHum.OnetBundleHub.dtos.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row in the reseller's order history table.
 *
 * Covers both direct reseller wallet orders (storefrontOrder = false)
 * and orders placed by customers through the reseller's public storefront
 * (storefrontOrder = true).
 *
 * ── Field guide ───────────────────────────────────────────────────────────────
 *
 *  profitGhc       sellingPriceGhc − costPriceGhc per order.
 *                  For direct reseller wallet orders where the reseller
 *                  sets no markup, this will be zero.
 *                  For storefront orders it reflects the reseller's margin.
 *
 *  storefrontOrder true  → placed by a customer via /store/{slug}
 *                  false → placed directly by the reseller from their own dashboard
 *                  The frontend uses this to show a "Storefront" vs "Direct" badge.
 */
@Data
@Builder
public class ResellerOrderResponse {

    private Long          id;
    private String        phoneNumber;

    /** PlatformSettings.Network.name() — e.g. "MTN", "TELECEL" */
    private String        network;

    private BigDecimal    capacityGb;
    private BigDecimal    costPriceGhc;
    private BigDecimal    sellingPriceGhc;

    /** sellingPriceGhc − costPriceGhc. Zero when prices are equal. */
    private BigDecimal    profitGhc;

    /** Order.OrderStatus.name() — "PENDING", "VERIFIED", "DELIVERED", "FAILED" */
    private String        status;
    /** Manual recipient review state; does not imply provider verification. */
    private String        recipientVerificationStatus;

    /** Order.PaymentMethod.name() — "WALLET" or "PAYSTACK" */
    private String        paymentMethod;

    /**
     * True when the order originated from a storefront customer.
     * False when placed directly by the reseller from their own dashboard.
     */
    private boolean       storefrontOrder;

    private LocalDateTime createdAt;
}