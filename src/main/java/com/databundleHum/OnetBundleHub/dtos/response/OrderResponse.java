package com.databundleHum.OnetBundleHub.dtos.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Returned for every order — guest, user wallet, reseller wallet, and storefront.
 * All enum fields are serialised to their .name() String so the frontend
 * receives plain text (e.g. "MTN", "PENDING", "WALLET").
 *
 * ── Field guide ───────────────────────────────────────────────────────────────
 *
 *  storefrontOrder     true when the order was placed through a reseller's public
 *                      storefront (/store/{slug}). The frontend uses this to show
 *                      a "Purchased via {resellerStoreName}" badge on the order card.
 *
 *  resellerStoreName   Display name of the storefront (storeName or fullName fallback).
 *                      Null when storefrontOrder = false.
 *
 *  profitGhc           sellingPriceGhc − costPriceGhc.
 *                      Populated only for RESELLER role orders (direct or storefront)
 *                      where the two prices differ. Zero for regular user orders.
 *                      Used on the reseller's order history table.
 *
 *  userEmail           (ADMIN VIEW) Email of the account that placed the order.
 *                      Null for guest orders (guest = true), since guest checkout
 *                      has no User row — use the synthetic guest email generated
 *                      at checkout time if you need something to display instead.
 *                      Added 2026-07-23 so admins can see who placed each order
 *                      alongside the phone number the bundle was sent to and the
 *                      data size purchased.
 *
 *  userPhone           (ADMIN VIEW) Account phone number of the buyer — i.e. the
 *                      number on their User profile, NOT the destination number
 *                      the bundle is delivered to (that's `phoneNumber` below).
 *                      A buyer can order data for a different number than their
 *                      own (e.g. buying for a friend/family member), so these two
 *                      numbers are frequently different and both matter to admins
 *                      reviewing an order. Null for guest orders (no User row).
 *                      Added 2026-07-23.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderResponse {

    private Long       id;

    /** Destination number the bundle is delivered to — NOT necessarily the buyer's own number. */
    private String     phoneNumber;

    /** PlatformSettings.Network.name() — e.g. "MTN", "TELECEL", "AT_PREMIUM" */
    private String     network;

    private BigDecimal capacityGb;
    private BigDecimal costPriceGhc;
    private BigDecimal sellingPriceGhc;

    /**
     * sellingPriceGhc − costPriceGhc.
     * Non-zero only for reseller orders where the reseller charges more than cost.
     * Always zero for regular user and guest orders.
     */
    private BigDecimal profitGhc;

    /** Order.PaymentMethod.name() — "WALLET" or "PAYSTACK" */
    private String     paymentMethod;

    /** Paystack transaction reference. Null for wallet-funded orders. */
    private String     paystackRef;

    /** Korapay refund lifecycle status, when a paid order has been refunded. */
    private String     korapayRefundStatus;

    /** Merchant refund reference used to correlate Korapay refund callbacks. */
    private String     korapayRefundReference;

    /** Order.OrderStatus.name() — "PENDING", "VERIFIED", "DELIVERED", "FAILED" */
    private String     status;

    /** Provider/platform explanation when the order failed. */
    private String     failureReason;

    /** True when placed without a user account (Paystack guest checkout). */
    private boolean    guest;

    /**
     * True when the order originated from a reseller's public storefront.
     * When true, resellerStoreName is populated.
     */
    private boolean    storefrontOrder;

    /**
     * Effective display name of the reseller's store (storeName or fullName fallback).
     * Null when storefrontOrder = false.
     */
    private String     resellerStoreName;

    /**
     * Email of the account that placed this order. Null for guest orders
     * (order.getUser() == null). Populated for the admin all-orders view so
     * admins can see who placed each order.
     */
    private String     userEmail;

    /** Full name on the account that placed the order. Null for guest orders. */
    private String     userFullName;

    /**
     * Account phone number of the buyer (their User.phone) — distinct from
     * `phoneNumber` above, which is the destination number the bundle is
     * sent to. Null for guest orders.
     */
    private String     userPhone;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private String authorizationUrl;
}
