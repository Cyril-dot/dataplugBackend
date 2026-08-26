package com.databundleHum.OnetBundleHub.dtos.response;

import lombok.Builder;
import lombok.Data;

/**
 * Response from DataBossHub's POST /checker/check.
 *
 * Unlike DataPrimo's catalog/order responses (which were parsed defensively
 * because the exact field shape was unconfirmed), this shape comes from a
 * fully worked example in DataBossHub's own guide, so it's mapped to a real
 * typed DTO directly.
 *
 * IMPORTANT: this is a PHONE-level signal, not an ORDER-level one.
 * "delivered" means "this phone has received at least one delivery
 * recently" — it does NOT confirm which specific order that was, and
 * cannot distinguish between multiple orders sent to the same number.
 * Treat this as corroborating evidence for a human reviewing an order,
 * never as automated proof that one particular order succeeded.
 */
@Data
@Builder
public class PhoneDeliveryCheckResponse {
    private String phone;
    /** "delivered" | "not_delivered" per DataBossHub's documented values. */
    private String status;
    private String latestOrder;
    private String lastDelivery;
    private Integer deliveryCount;
    private String message;
}
