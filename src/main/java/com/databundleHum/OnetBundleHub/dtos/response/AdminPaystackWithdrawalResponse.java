package com.databundleHum.OnetBundleHub.dtos.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminPaystackWithdrawalResponse {
    private Long id;
    private String reference;
    private BigDecimal amountGhc;
    private String payoutType;
    private String accountName;
    private String maskedAccountNumber;
    private String bankCode;
    private String reason;
    private String status;
    private String transferCode;
    private String failureReason;
    private String requestedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
