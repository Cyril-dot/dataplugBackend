package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.dtos.*;
import com.databundleHum.OnetBundleHub.dtos.response.*;
import com.databundleHum.OnetBundleHub.dtos.response.ResellerDashboardResponse;
import com.databundleHum.OnetBundleHub.entity.*;
import com.databundleHum.OnetBundleHub.entity.WalletTransaction.TransactionType;
import com.databundleHum.OnetBundleHub.repos.*;
import com.databundleHum.OnetBundleHub.security.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reseller service implementation.
 *
 * Covers:
 *   - Application & approval flow (GHS 20 fee, PENDING → APPROVED by admin)
 *   - Store settings (name, tagline, logo URL, theme colour, WhatsApp, Instagram,
 *     banner image, welcome message, button style, store theme)
 *   - Share info (store link, referral link)
 *   - Pricing table management (upsert / delete)
 *   - Storefront order history
 *   - Sub-customer list (users who registered via referral link)
 *   - Payout requests
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResellerServiceImpl implements ResellerService {

    private static final BigDecimal REGISTRATION_FEE = new BigDecimal("20.00");

    /** Pattern used to sanitise slug candidates. Keeps only lowercase letters, digits, hyphens. */
    private static final Pattern SLUG_UNSAFE = Pattern.compile("[^a-z0-9-]");

    private final UserRepository              userRepository;
    private final ResellerProfileRepository   resellerProfileRepository;
    private final ResellerPricingRepository   resellerPricingRepository;
    private final OrderRepository             orderRepository;
    private final PayoutRepository            payoutRepository;
    private final PlatformSettingsRepository  platformSettingsRepository;
    private final WalletService               walletService;
    private final AppUrlProvider              appUrlProvider;
    private final UnverifiedRecipientService unverifiedRecipientService;

    @Value("${app.reseller.min-payout-ghc:5.00}")
    private BigDecimal minPayoutGhc;

    // ── Application & profile ─────────────────────────────────────────────────

    @Override
    @Transactional
    public ResellerApplicationResponse applyForReseller(UUID userId,
                                                        ResellerApplicationRequest request) {
        User user = findUserOrThrow(userId);

        if (resellerProfileRepository.existsByUser(user)) {
            throw new DuplicateApplicationException(
                    "A reseller application already exists for this account.");
        }

        if (user.getRole() == User.Role.RESELLER) {
            throw new AlreadyResellerException("This account already has reseller status.");
        }

        walletService.debit(userId, REGISTRATION_FEE, TransactionType.RESELLER_FEE,
                "Reseller registration fee", null);

        ResellerProfile profile = ResellerProfile.builder()
                .user(user)
                .status(ResellerProfile.ResellerStatus.PENDING)
                .applicationNote(request.getApplicationNote())
                .build();

        profile = resellerProfileRepository.save(profile);
        log.info("[RESELLER] Application submitted: userId={} profileId={}", userId, profile.getId());

        return ResellerApplicationResponse.builder()
                .profileId(profile.getId())
                .status(profile.getStatus().name())
                .message("Application submitted. You will be notified once reviewed.")
                .walletBalanceAfter(walletService.getBalance(userId))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ResellerDashboardResponse getDashboard(UUID userId) {
        User user = findUserOrThrow(userId);
        ResellerProfile profile = findProfileOrThrow(user);

        long totalOrders = orderRepository.countByUserAndOrderedByRole(
                user, Order.OrderedByRole.RESELLER);

        return ResellerDashboardResponse.builder()
                .profileId(profile.getId())
                .status(profile.getStatus().name())
                .storeSlug(profile.getStoreSlug())
                .storeName(profile.getEffectiveStoreName())
                .totalRevenueGhc(profile.getTotalRevenueGhc())
                .totalCostGhc(profile.getTotalCostGhc())
                .totalProfitGhc(profile.getTotalProfitGhc())
                .profitPaidGhc(profile.getProfitPaidGhc())
                .availableProfitGhc(profile.getAvailableProfitGhc())
                .walletBalanceGhc(user.getWalletBalance())
                .approvedAt(profile.getApprovedAt())
                .totalOrders(totalOrders)
                .build();
    }

    // ── Store settings ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public StoreSettingsResponse getStoreSettings(UUID userId) {
        User user = findUserOrThrow(userId);
        ResellerProfile profile = findProfileOrThrow(user);
        assertApprovedReseller(profile);
        return toStoreSettingsResponse(profile);
    }

    @Override
    @Transactional
    public StoreSettingsResponse updateStoreSettings(UUID userId,
                                                     UpdateStoreSettingsRequest request) {
        User user = findUserOrThrow(userId);
        ResellerProfile profile = findProfileOrThrow(user);
        assertApprovedReseller(profile);

        // ── Existing fields ───────────────────────────────────────────────────

        if (request.getStoreName() != null) {
            profile.setStoreName(request.getStoreName().trim());
        }
        if (request.getStoreTagline() != null) {
            profile.setStoreTagline(request.getStoreTagline().trim());
        }
        if (request.getStoreLogoUrl() != null) {
            profile.setStoreLogoUrl(request.getStoreLogoUrl().trim());
        }
        if (request.getThemeColour() != null) {
            String colour = request.getThemeColour().trim();
            if (!colour.matches("^#[0-9A-Fa-f]{6}$")) {
                throw new ValidationException(
                        "themeColour must be a valid hex colour, e.g. #1A73E8. Got: " + colour);
            }
            profile.setThemeColour(colour);
        }

        // ── New fields ────────────────────────────────────────────────────────

        if (request.getWhatsappNumber() != null) {
            // Strip spaces and dashes before saving (e.g. "024 123-4567" → "0241234567")
            String cleaned = request.getWhatsappNumber().replaceAll("[\\s\\-]", "");
            profile.setWhatsappNumber(cleaned);
        }

        if (request.getInstagramHandle() != null) {
            // Strip a leading '@' if the reseller accidentally typed it
            String handle = request.getInstagramHandle().trim();
            if (handle.startsWith("@")) {
                handle = handle.substring(1);
            }
            profile.setInstagramHandle(handle);
        }

        if (request.getBannerImageUrl() != null) {
            profile.setBannerImageUrl(request.getBannerImageUrl().trim());
        }

        if (request.getWelcomeMessage() != null) {
            profile.setWelcomeMessage(request.getWelcomeMessage().trim());
        }

        if (request.getButtonStyle() != null) {
            profile.setButtonStyle(
                    ResellerProfile.ButtonStyle.valueOf(request.getButtonStyle()));
        }

        if (request.getStoreTheme() != null) {
            profile.setStoreTheme(
                    ResellerProfile.StoreTheme.valueOf(request.getStoreTheme()));
        }

        profile = resellerProfileRepository.save(profile);
        log.info("[RESELLER] Store settings updated: userId={} profileId={}", userId, profile.getId());
        return toStoreSettingsResponse(profile);
    }

    @Override
    @Transactional(readOnly = true)
    public StoreShareResponse getShareInfo(UUID userId) {
        User user = findUserOrThrow(userId);
        ResellerProfile profile = findProfileOrThrow(user);
        assertApprovedReseller(profile);

        String baseUrl     = appUrlProvider.getBaseUrl();
        String storeUrl    = baseUrl + "/store/" + profile.getStoreSlug();
        String referralUrl = baseUrl + "/ref/"   + profile.getStoreSlug();

        return StoreShareResponse.builder()
                .storeName(profile.getEffectiveStoreName())
                .storeSlug(profile.getStoreSlug())
                .storeUrl(storeUrl)
                .referralUrl(referralUrl)
                .build();
    }

    // ── Pricing ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<ResellerPricingResponse> getPricingTable(UUID userId, Pageable pageable) {
        User user = findUserOrThrow(userId);
        return resellerPricingRepository.findByReseller(user, pageable)
                .map(this::toPricingResponse);
    }

    @Override
    @Transactional
    public ResellerPricingResponse upsertPricing(UUID userId,
                                                 UpsertResellerPricingRequest request) {
        User user = findUserOrThrow(userId);
        assertApprovedReseller(findProfileOrThrow(user));

        BigDecimal costPrice = platformSettingsRepository
                .findResellerCostPrice(request.getNetwork(), request.getCapacityGb())
                .orElseThrow(() -> new BundleNotFoundException(
                        "No admin pricing found for network=" + request.getNetwork()
                                + " capacityGb=" + request.getCapacityGb()));

        if (request.getSellingPriceGhc().compareTo(costPrice) < 0) {
            throw new PriceBelowCostException(
                    "Selling price GHS " + request.getSellingPriceGhc()
                            + " is below reseller cost price of GHS " + costPrice);
        }

        ResellerPricing pricing = resellerPricingRepository
                .findByResellerAndNetworkAndCapacityGb(
                        user, request.getNetwork(), request.getCapacityGb())
                .orElseGet(() -> ResellerPricing.builder()
                        .reseller(user)
                        .network(request.getNetwork())
                        .capacityGb(request.getCapacityGb())
                        .build());

        pricing.setSellingPriceGhc(request.getSellingPriceGhc());
        pricing = resellerPricingRepository.save(pricing);

        log.info("[RESELLER] Pricing upserted: userId={} network={} gb={} price={}",
                userId, request.getNetwork(), request.getCapacityGb(),
                request.getSellingPriceGhc());

        return toPricingResponse(pricing);
    }

    @Override
    @Transactional
    public void deletePricing(UUID userId, Long pricingId) {
        User user = findUserOrThrow(userId);
        ResellerPricing pricing = resellerPricingRepository.findById(pricingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Pricing row not found: " + pricingId));

        if (!pricing.getReseller().getId().equals(user.getId())) {
            throw new ForbiddenException("You do not own this pricing row.");
        }

        resellerPricingRepository.delete(pricing);
        log.info("[RESELLER] Pricing deleted: pricingId={} userId={}", pricingId, userId);
    }

    // ── Orders ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<ResellerOrderResponse> getOrders(UUID userId, Pageable pageable) {
        User user = findUserOrThrow(userId);
        return orderRepository
                .findByUserAndOrderedByRoleOrderByCreatedAtDesc(
                        user, Order.OrderedByRole.RESELLER, pageable)
                .map(this::toOrderResponse);
    }

    // ── Sub-customers ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<SubCustomerResponse> getSubCustomers(UUID userId, Pageable pageable) {
        User user = findUserOrThrow(userId);
        findProfileOrThrow(user);

        return userRepository.findByReferredByResellerId(user.getId(), pageable)
                .map(sub -> {
                    long orderCount = orderRepository.countByUser(sub);
                    return SubCustomerResponse.builder()
                            .maskedName(maskName(sub.getFullName()))
                            .orderCount(orderCount)
                            .joinedAt(sub.getCreatedAt())
                            .build();
                });
    }

    // ── Payouts ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PayoutResponse requestPayout(UUID userId, PayoutRequest request) {
        User user = findUserOrThrow(userId);
        ResellerProfile profile = findProfileOrThrow(user);
        assertApprovedReseller(profile);

        if (request.getAmount().compareTo(minPayoutGhc) < 0) {
            throw new MinPayoutNotMetException(
                    "Minimum payout is GHS " + minPayoutGhc
                            + ". Requested: GHS " + request.getAmount());
        }

        BigDecimal available = profile.getAvailableProfitGhc();
        if (available.compareTo(request.getAmount()) < 0) {
            throw new InsufficientBalanceException(
                    "Available profit balance GHS " + available
                            + " is insufficient for payout of GHS " + request.getAmount());
        }

        Payout payout = Payout.builder()
                .reseller(user)
                .amount(request.getAmount())
                .mobileMoneyNumber(request.getMobileMoneyNumber())
                .network(request.getNetwork())
                .status(Payout.PayoutStatus.PENDING)
                .build();

        payout = payoutRepository.save(payout);
        log.info("[RESELLER] Payout requested: userId={} amount={} payoutId={}",
                userId, request.getAmount(), payout.getId());

        return toPayoutResponse(payout);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PayoutResponse> getPayoutHistory(UUID userId, Pageable pageable) {
        User user = findUserOrThrow(userId);
        return payoutRepository
                .findByResellerOrderByCreatedAtDesc(user, pageable)
                .map(this::toPayoutResponse);
    }

    // ── Slug generation ───────────────────────────────────────────────────────

    public String generateUniqueSlug(String fullName) {
        String base = Normalizer.normalize(fullName, Normalizer.Form.NFD)
                .toLowerCase()
                .replaceAll("\\s+", "-")
                .replaceAll(SLUG_UNSAFE.pattern(), "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");

        String candidate = base + "-data";

        if (!resellerProfileRepository.existsByStoreSlug(candidate)) {
            return candidate;
        }

        int suffix = 2;
        while (true) {
            String withSuffix = candidate + "-" + suffix;
            if (!resellerProfileRepository.existsByStoreSlug(withSuffix)) {
                return withSuffix;
            }
            suffix++;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private ResellerProfile findProfileOrThrow(User user) {
        return resellerProfileRepository.findByUser(user)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No reseller profile found for userId=" + user.getId()));
    }

    private void assertApprovedReseller(User user) {
        assertApprovedReseller(findProfileOrThrow(user));
    }

    private void assertApprovedReseller(ResellerProfile profile) {
        if (profile.getStatus() != ResellerProfile.ResellerStatus.APPROVED) {
            throw new ResellerNotApprovedException(
                    "Reseller profile is not approved. Current status: " + profile.getStatus());
        }
    }

    private String maskName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "Unknown";
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) return parts[0];
        return parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private StoreSettingsResponse toStoreSettingsResponse(ResellerProfile p) {
        return StoreSettingsResponse.builder()
                .profileId(p.getId())
                .storeSlug(p.getStoreSlug())
                .storeName(p.getEffectiveStoreName())
                .storeTagline(p.getStoreTagline())
                .storeLogoUrl(p.getStoreLogoUrl())
                .themeColour(p.getThemeColour())
                // New fields
                .whatsappNumber(p.getWhatsappNumber())
                .instagramHandle(p.getInstagramHandle())
                .bannerImageUrl(p.getBannerImageUrl())
                .welcomeMessage(p.getWelcomeMessage())
                .buttonStyle(p.getButtonStyle() != null ? p.getButtonStyle().name() : null)
                .storeTheme(p.getStoreTheme() != null ? p.getStoreTheme().name() : null)
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private ResellerPricingResponse toPricingResponse(ResellerPricing p) {
        return ResellerPricingResponse.builder()
                .id(p.getId())
                .network(p.getNetwork().name())
                .capacityGb(p.getCapacityGb())
                .sellingPriceGhc(p.getSellingPriceGhc())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private ResellerOrderResponse toOrderResponse(Order o) {
        return ResellerOrderResponse.builder()
                .id(o.getId())
                .phoneNumber(o.getPhoneNumber())
                .network(o.getNetwork().name())
                .capacityGb(o.getCapacityGb())
                .costPriceGhc(o.getCostPriceGhc())
                .sellingPriceGhc(o.getSellingPriceGhc())
                .profitGhc(o.getSellingPriceGhc().subtract(o.getCostPriceGhc()))
                .status(o.getStatus().name())
                .recipientVerificationStatus(unverifiedRecipientService.check(o.getPhoneNumber(), o.getNetwork()).getStatus())
                .paymentMethod(o.getPaymentMethod().name())
                .storefrontOrder(o.isStorefrontOrder())
                .createdAt(o.getCreatedAt())
                .build();
    }

    private PayoutResponse toPayoutResponse(Payout p) {
        return PayoutResponse.builder()
                .id(p.getId())
                .amount(p.getAmount())
                .mobileMoneyNumber(p.getMobileMoneyNumber())
                .network(p.getNetwork().name())
                .status(p.getStatus().name())
                .adminNote(p.getAdminNote())
                .paidAt(p.getPaidAt())
                .createdAt(p.getCreatedAt())
                .build();
    }
}