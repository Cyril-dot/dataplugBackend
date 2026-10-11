package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.dtos.*;
import com.databundleHum.OnetBundleHub.security.UserPrincipal;
import com.databundleHum.OnetBundleHub.services.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Authentication & profile controller.
 *
 * <p>Public endpoints: register, login, refresh, reset-password.
 * <p>Authenticated endpoints: logout, change-password, profile (GET/PUT).
 *
 * <p>Base path: {@code /api/v1/auth}
 *
 * <p>Referral attribution: the {@code /register} endpoint reads the
 * {@code ref_reseller_id} and {@code ref_affiliate} cookies (set earlier by
 * the frontend's {@code /ref/:slug} and {@code /a/:code} redirect routes)
 * and copies them onto the {@link RegisterRequest} before delegating to
 * {@link AuthService#register}. Reading them here — rather than relying on
 * the frontend to read {@code document.cookie} and include them in the JSON
 * body — means attribution works automatically on every request and can't
 * be dropped by a frontend bug.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Registration, login, token management, and profile")
public class AuthController {

    private final AuthService authService;

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Resolves the authenticated user's UUID from the UserPrincipal attached
     * to the Spring Security context by JwtAuthFilter. Call only from secured endpoints.
     */
    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        return principal.userId();
    }

    // ── Public endpoints ──────────────────────────────────────────────────────

    @PostMapping("/register")
    @Operation(summary = "Register a new user account")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User registered, tokens issued"),
            @ApiResponse(responseCode = "409", description = "Email or phone already in use")
    })
    public ResponseEntity<TokenResponse> register(
            @Valid @RequestBody RegisterRequest request,
            @CookieValue(name = "ref_reseller_id", required = false) String resellerSlugCookie,
            @CookieValue(name = "ref_affiliate", required = false) String affiliateCodeCookie
    ) {
        // Cookie values are the source of truth for referral attribution —
        // copy them onto the request before it reaches the service layer.
        if (resellerSlugCookie != null && !resellerSlugCookie.isBlank()) {
            request.setResellerSlug(resellerSlugCookie);
        }
        if (affiliateCodeCookie != null && !affiliateCodeCookie.isBlank()) {
            request.setAffiliateCode(affiliateCodeCookie);
        }

        log.info("[AUTH] Register attempt for email={}", request.getEmail());
        TokenResponse response = authService.register(request);
        log.info("[AUTH] Registration successful for userId={}", response.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate and receive access + refresh tokens")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials"),
            @ApiResponse(responseCode = "403", description = "Account deactivated")
    })
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("[AUTH] Login attempt for email={}", request.getEmail());
        TokenResponse response = authService.login(request);
        log.info("[AUTH] Login successful for userId={}", response.getUserId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new token pair (token rotation)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tokens refreshed"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token")
    })
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        log.debug("[AUTH] Refresh token request received");
        TokenResponse response = authService.refreshToken(request.getRefreshToken());
        log.debug("[AUTH] Refresh token rotated for userId={}", response.getUserId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Send a one-time password reset code by email")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "If the email is registered, a reset code was sent")
    })
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset-password/confirm")
    @Operation(summary = "Complete password reset using the emailed one-time code")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password reset successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid, expired, or already-used reset code")
    })
    public ResponseEntity<Void> completePasswordReset(
            @Valid @RequestBody CompletePasswordResetRequest request) {
        authService.completePasswordReset(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Reset password using email + current password (unauthenticated flow)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password reset successfully"),
            @ApiResponse(responseCode = "400", description = "Passwords do not match or same as current"),
            @ApiResponse(responseCode = "401", description = "Previous password is incorrect"),
            @ApiResponse(responseCode = "404", description = "No account found for that email")
    })
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        log.info("[AUTH] Password reset requested for email={}", request.getEmail());
        authService.resetPassword(request);
        log.info("[AUTH] Password reset successful for email={}", request.getEmail());
        return ResponseEntity.noContent().build();
    }

    // ── Authenticated endpoints ───────────────────────────────────────────────

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Revoke all refresh tokens (logout from all devices)")
    @ApiResponse(responseCode = "204", description = "Logged out successfully")
    public ResponseEntity<Void> logout() {
        UUID userId = currentUserId();
        log.info("[AUTH] Logout requested for userId={}", userId);
        authService.logout(userId);
        log.info("[AUTH] Logout successful for userId={}", userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Change password while authenticated")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password changed successfully"),
            @ApiResponse(responseCode = "400", description = "Passwords do not match or same as current"),
            @ApiResponse(responseCode = "401", description = "Current password is incorrect")
    })
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        UUID userId = currentUserId();
        log.info("[AUTH] Change password requested for userId={}", userId);
        authService.changePassword(userId, request);
        log.info("[AUTH] Password changed successfully for userId={}", userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get the authenticated user's profile")
    @ApiResponse(responseCode = "200", description = "Profile returned")
    public ResponseEntity<ProfileResponse> getProfile() {
        UUID userId = currentUserId();
        log.debug("[AUTH] Get profile for userId={}", userId);
        return ResponseEntity.ok(authService.getProfile(userId));
    }

    @PutMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update the authenticated user's profile (name and/or phone)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile updated"),
            @ApiResponse(responseCode = "409", description = "Phone number already in use")
    })
    public ResponseEntity<ProfileResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        UUID userId = currentUserId();
        log.info("[AUTH] Update profile for userId={}", userId);
        ProfileResponse response = authService.updateProfile(userId, request);
        log.info("[AUTH] Profile updated successfully for userId={}", userId);
        return ResponseEntity.ok(response);
    }
}
