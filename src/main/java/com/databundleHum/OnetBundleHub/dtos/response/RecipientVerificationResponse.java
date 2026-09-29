package com.databundleHum.OnetBundleHub.dtos.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RecipientVerificationResponse {
    private String phoneNumber;
    private String network;
    /** NOT_APPLICABLE, NOT_REPORTED, UNVERIFIED, SUBMITTED, or VERIFIED. */
    private String status;
    /** False for all known unverified/submitted records. Never means auto-verified. */
    private boolean canPlaceOrder;
    private String message;
    private Integer attempts;
    private String failureReason;
    private LocalDateTime lastFailedAt;
    private LocalDateTime verifiedAt;
    private String verifiedBy;
}
