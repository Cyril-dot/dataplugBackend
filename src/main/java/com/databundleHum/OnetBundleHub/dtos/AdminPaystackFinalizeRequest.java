package com.databundleHum.OnetBundleHub.dtos;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminPaystackFinalizeRequest {
    @NotBlank(message = "Paystack OTP is required")
    private String otp;
}
