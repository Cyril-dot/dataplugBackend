package com.databundleHum.OnetBundleHub.dtos;

import com.databundleHum.OnetBundleHub.entity.PlatformSettings;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class BulkNumberVerificationRequest {
    @NotEmpty(message = "At least one phone number is required")
    @Size(max = 1000, message = "You can submit up to 1000 numbers at a time")
    private List<String> numbers;

    @NotNull(message = "Network is required")
    private PlatformSettings.Network network;
}
