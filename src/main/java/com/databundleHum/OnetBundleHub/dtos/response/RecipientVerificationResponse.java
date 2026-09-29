package com.databundleHum.OnetBundleHub.dtos.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RecipientVerificationResponse {
    private String phoneNumber;
    private String network;
    /** NOT_APPLICABLE or PROVIDER_CHECK_ON_ORDER; local audit states are not order authorization. */
    private String status;
    /** Informational only; the actual BigDreams order response is authoritative. */
    private boolean canPlaceOrder;
    private String message;
    private Integer attempts;
    private String failureReason;
    private LocalDateTime lastFailedAt;
    private LocalDateTime verifiedAt;
    private String verifiedBy;
}
