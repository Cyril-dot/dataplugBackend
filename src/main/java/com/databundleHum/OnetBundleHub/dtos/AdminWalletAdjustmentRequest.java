package com.databundleHum.OnetBundleHub.dtos;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminWalletAdjustmentRequest {

    @NotNull(message = "Choose whether to credit or debit the wallet.")
    private Action action;

    @NotNull(message = "Enter an amount.")
    @DecimalMin(value = "0.01", message = "Amount must be at least GHS 0.01.")
    @Digits(integer = 8, fraction = 2, message = "Amount must have no more than two decimal places.")
    private BigDecimal amount;

    @NotBlank(message = "Provide a reason for this adjustment.")
    @Size(max = 500, message = "Reason must be 500 characters or fewer.")
    private String reason;

    public enum Action {
        CREDIT,
        DEBIT
    }
}
