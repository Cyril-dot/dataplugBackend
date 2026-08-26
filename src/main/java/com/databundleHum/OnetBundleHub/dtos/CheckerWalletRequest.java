package com.databundleHum.OnetBundleHub.dtos;

import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for POST /api/checkers/wallet/purchase
 * User identity comes from the authenticated JWT, not this body.
 */
@Data
public class CheckerWalletRequest {
    @NotBlank
    private String phoneNumber;
    @NotNull
    private CheckerPricing.ExamType examType;
}
