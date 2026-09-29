package com.databundleHum.OnetBundleHub.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MtnRecipientVerificationRequest {

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^0[2359]\\d{8}$", message = "Invalid Ghana phone number")
    private String phoneNumber;
}
