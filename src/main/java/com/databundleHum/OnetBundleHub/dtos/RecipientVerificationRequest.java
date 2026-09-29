package com.databundleHum.OnetBundleHub.dtos;

import com.databundleHum.OnetBundleHub.entity.PlatformSettings;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RecipientVerificationRequest {
    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    @NotNull(message = "Network is required")
    private PlatformSettings.Network network;
}
