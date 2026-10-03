package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.dtos.BulkNumberVerificationRequest;
import com.databundleHum.OnetBundleHub.dtos.response.BulkNumberVerificationResponse;
import com.databundleHum.OnetBundleHub.security.UserPrincipal;
import com.databundleHum.OnetBundleHub.services.UnverifiedRecipientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/recipient-verification")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class RecipientVerificationController {
    private final UnverifiedRecipientService unverifiedRecipientService;

    @PostMapping("/submissions")
    public ResponseEntity<BulkNumberVerificationResponse> submitNumbers(
            @Valid @RequestBody BulkNumberVerificationRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(unverifiedRecipientService.submitNumbers(
                principal.userId(), request.getNumbers(), request.getNetwork()));
    }
}
