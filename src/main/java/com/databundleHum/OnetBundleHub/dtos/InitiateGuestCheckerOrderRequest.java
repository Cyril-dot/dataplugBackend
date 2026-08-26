package com.databundleHum.OnetBundleHub.dtos;

import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for POST /api/checkers/guest/initiate
 */
@Data
public class InitiateGuestCheckerOrderRequest {
    @NotBlank
    private String phoneNumber;
    @NotNull
    private CheckerPricing.ExamType examType;
}
