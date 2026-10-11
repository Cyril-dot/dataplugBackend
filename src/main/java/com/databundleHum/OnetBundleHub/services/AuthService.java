package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.dtos.*;
import com.databundleHum.OnetBundleHub.entity.PasswordResetToken;
import com.databundleHum.OnetBundleHub.entity.RefreshToken;
import com.databundleHum.OnetBundleHub.entity.User;
import com.databundleHum.OnetBundleHub.entity.User.Role;
import com.databundleHum.OnetBundleHub.repos.RefreshTokenRepository;
import com.databundleHum.OnetBundleHub.repos.PasswordResetTokenRepository;
import com.databundleHum.OnetBundleHub.repos.UserRepository;
import com.databundleHum.OnetBundleHub.security.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Authentication and profile service.
 *
 * Changes from previous version:
 *
 *   register() now accepts two optional referral identifiers:
 *     - affiliateCode   (from cookie ref_affiliate set by /a/{code} redirect)
 *     - resellerSlug    (from cookie ref_reseller_id set by /ref/{slug} redirect)
 *
 *   Both are captured by the controller from the request cookies and passed
 *   into RegisterRequest. If present and valid, they are resolved to User FKs
 *   and stored on the new User row.
 *
 *   Self-referral prevention (architecture §9):
 *     After the new User is saved, we check if referredByAffiliate.id == newUser.id
 *     or referredByReseller.id == newUser.id and clear the FK if so.
 *     This second check is the backend safety net; the frontend should also
 *     not pass the cookie if the user is already logged in.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom RESET_CODE_RANDOM = new SecureRandom();

    private final UserRepository         userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final JwtUtil                jwtUtil;
    private final PasswordEncoder        passwordEncoder;
    private final AuthenticationManager  authenticationManager;
    private final NotificationService    notificationService;
    private final AffiliateService       affiliateService;

    @Value("${app.jwt.refresh-token-expiry-ms:604800000}")
    private long refreshTokenExpiryMs;

    // ── Register ──────────────────────────────────────────────────────────────

    /**
     * Register a new user.
     *
     * Referral attribution:
     *   The controller passes optional affiliateCode and resellerSlug fields from
     *   the registration cookies. Both are resolved here to User FKs.
     *
     *   First-touch attribution: if the cookie was set earlier (30-day TTL),
     *   it still applies — no extra logic needed here, the cookie value
     *   is simply passed in from the frontend.
     *
     *   Self-referral guard: if the affiliate or reseller resolves to the same
     *   user who is registering (edge case — same browser), the FK is cleared.
     */
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail().toLowerCase())) {
            throw new ConflictException("An account with this email already exists.");
        }
        if (userRepository.existsByPhone(request.getPhone())) {
            throw new ConflictException("An account with this phone number already exists.");
        }

        // Resolve referral cookies → User entities (null if not present / invalid)
        User referredByAffiliate = affiliateService.resolveAffiliateCode(
                request.getAffiliateCode());
        User referredByReseller  = affiliateService.resolveResellerSlug(
                request.getResellerSlug());

        User user = User.builder()
                .fullName(request.getFullName())
                .email(request.getEmail().toLowerCase())
                .phone(request.getPhone())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .referredByAffiliate(referredByAffiliate)
                .referredByReseller(referredByReseller)
                .build();

        user = userRepository.save(user);

        // ── Self-referral guard (backend safety net) ──────────────────────────
        // After the user is saved and has an ID, verify the referrer is not themselves.
        boolean selfReferralFixed = false;

        if (referredByAffiliate != null
                && referredByAffiliate.getId().equals(user.getId())) {
            user.setReferredByAffiliate(null);
            selfReferralFixed = true;
            log.warn("[AUTH] Self-referral via affiliate code blocked: userId={}", user.getId());
        }
        if (referredByReseller != null
                && referredByReseller.getId().equals(user.getId())) {
            user.setReferredByReseller(null);
            selfReferralFixed = true;
            log.warn("[AUTH] Self-referral via reseller slug blocked: userId={}", user.getId());
        }
        if (selfReferralFixed) {
            user = userRepository.save(user);
        }

        // ── Attribution logging ───────────────────────────────────────────────
        if (user.getReferredByAffiliate() != null) {
            log.info("[AUTH] New user attributed to affiliate: newUserId={} affiliateId={}",
                    user.getId(), user.getReferredByAffiliate().getId());
        }
        if (user.getReferredByReseller() != null) {
            log.info("[AUTH] New user attributed to reseller: newUserId={} resellerId={}",
                    user.getId(), user.getReferredByReseller().getId());
        }

        try {
            notificationService.sendWelcomeEmail(user.getEmail(), user.getFullName());
        } catch (Exception e) {
            log.warn("[AUTH] Failed to send welcome email to {}: {}", user.getEmail(), e.getMessage());
        }

        log.info("[AUTH] New user registered: userId={} email={}", user.getId(), user.getEmail());
        return issueTokens(user);
    }

    // ── Login ──────────────────────────────────────────────────────────────────

    @Transactional
    public TokenResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail().toLowerCase(),
                            request.getPassword()
                    )
            );
        } catch (DisabledException e) {
            throw new AccountDeactivatedException(
                    "Your account has been deactivated. Please contact support.");
        } catch (BadCredentialsException e) {
            throw new InvalidCredentialsException("Invalid email or password.");
        }

        User user = userRepository.findByEmail(request.getEmail().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        refreshTokenRepository.revokeAllByUserId(user.getId());

        log.info("[AUTH] User logged in: userId={}", user.getId());
        return issueTokens(user);
    }

    // ── Refresh token ──────────────────────────────────────────────────────────

    @Transactional
    public TokenResponse refreshToken(String rawRefreshToken) {
        RefreshToken stored = refreshTokenRepository
                .findByTokenAndRevokedFalse(rawRefreshToken)
                .orElseThrow(() -> new InvalidTokenException(
                        "Invalid or revoked refresh token."));

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            stored.setRevoked(true);
            refreshTokenRepository.save(stored);
            throw new InvalidTokenException("Refresh token has expired. Please log in again.");
        }

        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        User user = stored.getUser();
        log.debug("[AUTH] Refresh token rotated for userId={}", user.getId());
        return issueTokens(user);
    }

    // ── Logout ─────────────────────────────────────────────────────────────────

    @Transactional
    public void logout(UUID userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
        log.info("[AUTH] User logged out: userId={}", userId);
    }

    // ── Forgot password (email OTP) ───────────────────────────────────────────

    /** Starts recovery without revealing whether an email is registered. */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        userRepository.findByEmail(email).ifPresent(user -> {
            passwordResetTokenRepository.invalidateAllForUser(email);
            String otp = String.format("%06d", RESET_CODE_RANDOM.nextInt(1_000_000));
            passwordResetTokenRepository.save(PasswordResetToken.builder()
                    .otp(otp)
                    .user(user)
                    .expiresAt(Instant.now().plusSeconds(600))
                    .used(false)
                    .createdAt(Instant.now())
                    .build());
            notificationService.sendPasswordResetOtp(user.getEmail(), user.getFullName(), otp);
            log.info("[AUTH] Password reset code generated for userId={}", user.getId());
        });
    }

    @Transactional
    public void completePasswordReset(CompletePasswordResetRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new ValidationException("Passwords do not match.");
        }

        String email = request.getEmail().trim().toLowerCase();
        PasswordResetToken token = passwordResetTokenRepository
                .findByOtpAndUsedFalse(request.getOtp())
                .filter(candidate -> candidate.getExpiresAt().isAfter(Instant.now()))
                .filter(candidate -> candidate.getUser().getEmail().equalsIgnoreCase(email))
                .orElseThrow(() -> new ValidationException("That reset code is invalid or expired."));

        User user = token.getUser();
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new ValidationException("New password must be different from your current password.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        token.setUsed(true);
        passwordResetTokenRepository.save(token);
        refreshTokenRepository.revokeAllByUserId(user.getId());
        notificationService.sendPasswordChangedAlert(user.getEmail(), user.getFullName());
        log.info("[AUTH] Password reset completed for userId={}", user.getId());
    }

    // ── Reset password (unauthenticated — email + previous password) ───────────

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new ValidationException("Passwords do not match.");
        }

        User user = userRepository.findByEmail(request.getEmail().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No account found with that email."));

        if (!passwordEncoder.matches(request.getPreviousPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Previous password is incorrect.");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new ValidationException(
                    "New password must be different from your current password.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        refreshTokenRepository.revokeAllByUserId(user.getId());
        notificationService.sendPasswordChangedAlert(user.getEmail(), user.getFullName());
        log.info("[AUTH] Password reset for userId={}", user.getId());
    }

    // ── Change password (authenticated) ───────────────────────────────────────

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new ValidationException("Passwords do not match.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Current password is incorrect.");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new ValidationException(
                    "New password must be different from your current password.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        refreshTokenRepository.revokeAllByUserId(userId);
        notificationService.sendPasswordChangedAlert(user.getEmail(), user.getFullName());
        log.info("[AUTH] Password changed for userId={}", userId);
    }

    // ── Update profile ─────────────────────────────────────────────────────────

    @Transactional
    public ProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName().trim());
        }

        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            String newPhone = request.getPhone().trim();
            if (!newPhone.equals(user.getPhone())) {
                if (userRepository.existsByPhone(newPhone)) {
                    throw new ConflictException(
                            "This phone number is already registered to another account.");
                }
                user.setPhone(newPhone);
            }
        }

        userRepository.save(user);
        log.info("[AUTH] Profile updated for userId={}", userId);
        return buildProfileResponse(user);
    }

    // ── Get profile ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
        return buildProfileResponse(user);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private TokenResponse issueTokens(User user) {
        String roleStr      = "ROLE_" + user.getRole().name();
        String accessToken  = jwtUtil.generateAccessToken(user.getEmail(), roleStr, user.getId());
        String refreshToken = jwtUtil.generateRefreshToken(user.getEmail());

        RefreshToken rt = RefreshToken.builder()
                .token(refreshToken)
                .user(user)
                .expiresAt(Instant.now().plusMillis(refreshTokenExpiryMs))
                .revoked(false)
                .build();
        refreshTokenRepository.save(rt);

        return TokenResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtUtil.getAccessTokenExpiryMs() / 1000)
                .role(user.getRole().name())
                .userId(user.getId())
                .fullName(user.getFullName())
                .walletBalance(user.getWalletBalance().doubleValue())
                .isAffiliate(user.isAffiliate())
                .build();
    }

    private ProfileResponse buildProfileResponse(User user) {
        return ProfileResponse.builder()
                .userId(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRole().name())
                .walletBalance(user.getWalletBalance().doubleValue())
                .active(user.isActive())
                .isAffiliate(user.isAffiliate())
                .affiliateCode(user.getAffiliateCode())
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toString() : null)
                .build();
    }
}
