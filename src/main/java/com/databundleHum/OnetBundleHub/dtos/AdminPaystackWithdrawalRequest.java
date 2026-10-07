package com.databundleHum.OnetBundleHub.dtos;

import com.databundleHum.OnetBundleHub.entity.PaystackWithdrawal;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminPaystackWithdrawalRequest {
    @NotNull(message = "Payout type is required")
    private PaystackWithdrawal.PayoutType payoutType;

    @NotBlank(message = "Account name is required")
    @Size(max = 160)
    private String accountName;

    @NotBlank(message = "Account number is required")
    @Size(max = 40)
    private String accountNumber;

    @NotBlank(message = "Paystack bank or mobile-money code is required")
    @Size(max = 40)
    private String bankCode;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "10.00", message = "Minimum withdrawal is GHS 10.00")
    @DecimalMax(value = "50000.00", message = "Maximum withdrawal is GHS 50,000.00")
    private BigDecimal amountGhc;

    @Size(max = 160)
    private String reason;
}
