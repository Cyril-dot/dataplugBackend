package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.config.AppConfig;
import com.databundleHum.OnetBundleHub.util.FrontendUrlResolver;
import com.databundleHum.OnetBundleHub.dtos.InitiateGuestStorefrontCheckerOrderRequest;
import com.databundleHum.OnetBundleHub.dtos.InitiateGuestStorefrontOrderRequest;
import com.databundleHum.OnetBundleHub.dtos.StorefrontResponse;
import com.databundleHum.OnetBundleHub.dtos.WalletOrderRequest;
import com.databundleHum.OnetBundleHub.dtos.CheckerWalletRequest;
import com.databundleHum.OnetBundleHub.dtos.response.CheckerOrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.InitiateCheckerOrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.OrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.StoreOverviewResponse;
import com.databundleHum.OnetBundleHub.entity.*;
import com.databundleHum.OnetBundleHub.entity.WalletTransaction.TransactionType;
import com.databundleHum.OnetBundleHub.repos.*;
import com.databundleHum.OnetBundleHub.security.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles all interactions with a reseller's public storefront.
 *
 * Public endpoint — no authentication required for browsing or guest checkout.
 * Wallet checkout requires the customer to be logged in (standard JWT auth).
 *
 * ── MIGRATION FROM PAYSTACK TO KORAPAY (2026-08-26) ──────────────────────────
 * (unchanged from prior version — see OrderService for the full writeup.)
 *
 * ── MIGRATION FROM BIG DREAMS TO DATAPRIMO (2026-08-26) ──────────────────────
 * (unchanged — bundle provisioning resolves via PlatformSettings.dataprimo*
 * and calls DataPrimoService.)
 *
 * ── CHECKER FEATURE (2026-08-26) ──────────────────────────────────────────
 * Adds a full parallel set of storefront flows for result checkers
 * (BECE/WASSCE), alongside the existing data-bundle flows:
 *   - getStorefront() now also returns a `checkers` list (reseller's custom
 *     checker prices), sourced the same way bundles are — via a
 *     CheckerResellerPricing row joined against active CheckerPricing.
 *   - initiateGuestStorefrontCheckerOrder / fulfilStorefrontCheckerKorapayOrder /
 *     placeWalletStorefrontCheckerOrder mirror the bundle equivalents exactly,
 *     but delegate the actual DataBossHub purchase to
 *     CheckerService.provisionFromStock(order) — the same shared method
 *     CheckerService itself uses for guest/wallet checker orders, so slot-
 *     contention retry logic lives in exactly one place.
 *   - getStoreOverview() is NEW — a single combined "everything about this
 *     store" view (branding + bundle prices + checker prices + financials)
 *     for the reseller's own dashboard or an admin inspecting a store.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResellerStorefrontService {

    private static final int DUPLICATE_WINDOW_SECONDS = 30;

    /** Must match CheckerService's constant. */
    private static final BigDecimal PROCESSING_CHARGE_RATE = new BigDecimal("0.10");

    private final ResellerProfileRepository  resellerProfileRepository;
    private final ResellerPricingRepository  resellerPricingRepository;
    private final PlatformSettingsRepository platformSettingsRepository;
    private final OrderRepository            orderRepository;
    private final UserRepository             userRepository;
    private final WalletService              walletService;
    private final KorapayService             korapayService;
    private final DataPrimoService           dataPrimoService;
    private final NotificationService        notificationService;
    private final AppConfig appConfig;
    private final FrontendUrlResolver frontendUrlResolver;

    // ── Checker feature dependencies (NEW) ─────────────────────────────────────
    private final CheckerOrderRepository            checkerOrderRepository;
    private final CheckerPricingRepository          checkerPricingRepository;
    private final CheckerResellerPricingRepository  checkerResellerPricingRepository;
    private final CheckerStockRepository            checkerStockRepository;
    private final CheckerService                    checkerService;

    // ── Storefront browse ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public StorefrontResponse getStorefront(String slug) {
        log.info("[STOREFRONT] getStorefront: slug={}", slug);

        ResellerProfile profile = findProfileBySlugOrThrow(slug);

        if (profile.getStatus() != ResellerProfile.ResellerStatus.APPROVED) {
            throw new ResourceNotFoundException("Store not found: " + slug);
        }

        List<ResellerPricing> pricingRows = resellerPricingRepository
                .findByResellerWithActivePlatformSettings(profile.getUser());

        List<StorefrontResponse.BundleItem> bundles = pricingRows.stream()
                .map(p -> StorefrontResponse.BundleItem.builder()
                        .network(p.getNetwork().name())
                        .capacityGb(p.getCapacityGb())
                        .sellingPriceGhc(p.getSellingPriceGhc())
                        .build())
                .collect(Collectors.toList());

        List<CheckerResellerPricing> checkerPricingRows = checkerResellerPricingRepository
                .findByResellerWithActivePricing(profile.getUser());

        List<StorefrontResponse.CheckerItem> checkers = checkerPricingRows.stream()
                .map(p -> StorefrontResponse.CheckerItem.builder()
                        .examType(p.getExamType().name())
                        .sellingPriceGhc(p.getSellingPriceGhc())
                        .inStock(checkerStockRepository.countByExamTypeAndUsedFalse(p.getExamType()) > 0)
                        .build())
                .collect(Collectors.toList());

        log.debug("[STOREFRONT] Fetched store: slug={} bundleCount={} checkerCount={}",
                slug, bundles.size(), checkers.size());

        return StorefrontResponse.builder()
                .storeSlug(profile.getStoreSlug())
                .storeName(profile.getEffectiveStoreName())
                .storeTagline(profile.getStoreTagline())
                .storeLogoUrl(profile.getStoreLogoUrl())
                .themeColour(profile.getThemeColour())
                .whatsappNumber(profile.getWhatsappNumber())
                .instagramHandle(profile.getInstagramHandle())
                .bannerImageUrl(profile.getBannerImageUrl())
                .welcomeMessage(profile.getWelcomeMessage())
                .buttonStyle(profile.getButtonStyle() != null
                        ? profile.getButtonStyle().name() : null)
                .storeTheme(profile.getStoreTheme() != null
                        ? profile.getStoreTheme().name() : null)
                .bundles(bundles)
                .checkers(checkers)
                .build();
    }

    // ── Guest storefront bundle order ─────────────────────────────────────────

    @Transactional
    public OrderResponse initiateGuestStorefrontOrder(String slug,
                                                      InitiateGuestStorefrontOrderRequest request) {
        log.info("[STOREFRONT] initiateGuestStorefrontOrder: slug={} phone={} network={} gb={}",
                slug, request.getPhoneNumber(), request.getNetwork(), request.getCapacityGb());

        ResellerProfile profile   = findApprovedProfileBySlugOrThrow(slug);
        ResellerPricing pricing   = findResellerPricingOrThrow(profile.getUser(),
                request.getNetwork(), request.getCapacityGb());
        PlatformSettings settings = findActiveSettingsOrThrow(request.getNetwork(),
                request.getCapacityGb());

        BigDecimal sellingPrice = pricing.getSellingPriceGhc();
        BigDecimal costPrice    = settings.getResellerPriceGhc();

        String reference  = korapayService.generateReference();
        String guestEmail = buildGuestEmail(request.getPhoneNumber());
        String customerName = profile.getEffectiveStoreName() + " Customer";

        Map<String, Object> korapayData = korapayService.initiateTransaction(
                guestEmail,
                customerName,
                sellingPrice,
                reference,
                buildRedirectUrl(),
                Map.of(
                        "type",              "STOREFRONT_GUEST_ORDER",
                        "phone",             request.getPhoneNumber(),
                        "network",           request.getNetwork().name(),
                        "capacityGb",        request.getCapacityGb().toString(),
                        "resellerProfileId", profile.getId().toString()
                )
        );

        String authorizationUrl = (String) korapayData.get("checkout_url");

        Order order = Order.builder()
                .phoneNumber(request.getPhoneNumber())
                .network(request.getNetwork())
                .capacityGb(request.getCapacityGb())
                .costPriceGhc(costPrice)
                .sellingPriceGhc(sellingPrice)
                .paymentMethod(Order.PaymentMethod.PAYSTACK) // TODO: rename enum constant to KORAPAY in a follow-up migration
                .paystackRef(reference)                      // TODO: rename field to gatewayRef in a follow-up migration
                .status(Order.OrderStatus.PENDING)
                .guest(true)
                .orderedByRole(Order.OrderedByRole.RESELLER)
                .resellerProfile(profile)
                .storefrontOrder(true)
                .build();

        orderRepository.save(order);

        log.info("[STOREFRONT] Guest order initiated: slug={} orderId={} ref={} phone={} network={} gb={} price={}",
                slug, order.getId(), reference, request.getPhoneNumber(),
                request.getNetwork(), request.getCapacityGb(), sellingPrice);

        return toOrderResponse(order, authorizationUrl);
    }

    @Transactional
    public void fulfilStorefrontKorapayOrder(String reference) {
        log.info("[STOREFRONT] fulfilStorefrontKorapayOrder: ref={}", reference);

        Order order = orderRepository.findByPaystackRef(reference) // TODO: rename repo method to findByGatewayRef
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found for Korapay ref: " + reference));

        order.setStatus(Order.OrderStatus.VERIFIED);
        orderRepository.save(order);

        log.info("[STOREFRONT] Korapay order VERIFIED: orderId={} ref={}", order.getId(), reference);

        try {
            provisionOrder(order);
            updateResellerStats(order);
        } catch (UpstreamApiException ex) {
            log.error("[STOREFRONT] Bundle provision failed: orderId={} error={}", order.getId(), ex.getMessage());
            if (order.getUser() != null) {
                notificationService.sendOrderFailedAlert(
                        order.getUser().getEmail(), order.getUser().getFullName(), order.getId());
            }
        }
    }

    // ── Wallet storefront bundle order ────────────────────────────────────────

    @Transactional
    public OrderResponse placeWalletStorefrontOrder(String slug, UUID customerId,
                                                    WalletOrderRequest request) {
        log.info("[STOREFRONT] placeWalletStorefrontOrder: slug={} customerId={} phone={} network={} gb={}",
                slug, customerId, request.getPhoneNumber(), request.getNetwork(), request.getCapacityGb());

        User            customer  = findUserOrThrow(customerId);
        ResellerProfile profile   = findApprovedProfileBySlugOrThrow(slug);
        ResellerPricing pricing   = findResellerPricingOrThrow(profile.getUser(),
                request.getNetwork(), request.getCapacityGb());
        PlatformSettings settings = findActiveSettingsOrThrow(request.getNetwork(),
                request.getCapacityGb());

        BigDecimal sellingPrice = pricing.getSellingPriceGhc();
        BigDecimal costPrice    = settings.getResellerPriceGhc();

        walletService.debit(customerId, sellingPrice, TransactionType.PURCHASE,
                "Data bundle " + request.getCapacityGb() + "GB " + request.getNetwork()
                        + " via " + profile.getEffectiveStoreName(),
                null);

        Order order = Order.builder()
                .user(customer)
                .phoneNumber(request.getPhoneNumber())
                .network(request.getNetwork())
                .capacityGb(request.getCapacityGb())
                .costPriceGhc(costPrice)
                .sellingPriceGhc(sellingPrice)
                .paymentMethod(Order.PaymentMethod.WALLET)
                .status(Order.OrderStatus.PENDING)
                .guest(false)
                .orderedByRole(Order.OrderedByRole.RESELLER)
                .resellerProfile(profile)
                .storefrontOrder(true)
                .build();

        try {
            order = orderRepository.save(order);
        } catch (DataIntegrityViolationException ex) {
            log.warn("[STOREFRONT] Duplicate wallet storefront order blocked: slug={} customerId={}",
                    slug, customerId);
            throw new DuplicateOrderException(
                    "A similar order was already placed in the last "
                            + DUPLICATE_WINDOW_SECONDS + " seconds.");
        }

        log.info("[STOREFRONT] Wallet order placed: slug={} customerId={} orderId={} price={}",
                slug, customerId, order.getId(), sellingPrice);

        try {
            provisionOrder(order);
            updateResellerStats(order);
        } catch (UpstreamApiException ex) {
            log.error("[STOREFRONT] Bundle provision failed: orderId={} error={}",
                    order.getId(), ex.getMessage());

            walletService.credit(customerId, sellingPrice, TransactionType.REFUND,
                    "Refund: failed bundle delivery for order #" + order.getId(), null);

            notificationService.sendOrderFailedAlert(
                    customer.getEmail(), customer.getFullName(), order.getId());
        }

        return toOrderResponse(orderRepository.save(order));
    }

    // ── Guest storefront CHECKER order (NEW) ────────────────────────────────────

    @Transactional
    public InitiateCheckerOrderResponse initiateGuestStorefrontCheckerOrder(
            String slug, InitiateGuestStorefrontCheckerOrderRequest request) {
        log.info("[STOREFRONT] initiateGuestStorefrontCheckerOrder: slug={} phone={} examType={}",
                slug, request.getPhoneNumber(), request.getExamType());

        ResellerProfile profile = findApprovedProfileBySlugOrThrow(slug);
        CheckerResellerPricing pricing = checkerResellerPricingRepository
                .findByResellerAndExamType(profile.getUser(), request.getExamType())
                .orElseThrow(() -> new BundleNotFoundException(
                        "Checker not available on this store: examType=" + request.getExamType()));
        CheckerPricing platformPricing = checkerPricingRepository
                .findByExamTypeAndActiveTrue(request.getExamType())
                .orElseThrow(() -> new BundleNotFoundException(
                        "Checker not available: examType=" + request.getExamType()));

        BigDecimal sellingPrice = pricing.getSellingPriceGhc();

        String reference = korapayService.generateReference();
        String guestEmail = buildGuestEmail(request.getPhoneNumber());
        String customerName = profile.getEffectiveStoreName() + " Customer";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type",              "STOREFRONT_CHECKER_ORDER");
        metadata.put("phone",             request.getPhoneNumber());
        metadata.put("examType",          request.getExamType().name());
        metadata.put("resellerProfileId", profile.getId().toString());

        Map<String, Object> korapayData = korapayService.initiateTransaction(
                guestEmail, customerName, sellingPrice, reference, buildRedirectUrl(), metadata);

        CheckerOrder order = CheckerOrder.builder()
                .phoneNumber(request.getPhoneNumber())
                .examType(request.getExamType())
                .priceGhc(sellingPrice)
                .paymentMethod(CheckerOrder.PaymentMethod.KORAPAY)
                .gatewayRef(reference)
                .status(CheckerOrder.CheckerOrderStatus.PENDING)
                .guest(true)
                .resellerProfile(profile)
                .storefrontOrder(true)
                .build();
        checkerOrderRepository.save(order);

        log.info("[STOREFRONT] Guest checker order initiated: slug={} orderId={} ref={} phone={} examType={} price={}",
                slug, order.getId(), reference, request.getPhoneNumber(), request.getExamType(), sellingPrice);

        return InitiateCheckerOrderResponse.builder()
                .gatewayRef(reference)
                .checkoutUrl((String) korapayData.get("checkout_url"))
                .amountGhc(sellingPrice)
                .phoneNumber(request.getPhoneNumber())
                .examType(request.getExamType().name())
                .build();
    }

    @Transactional
    public void fulfilStorefrontCheckerKorapayOrder(String reference) {
        log.info("[STOREFRONT] fulfilStorefrontCheckerKorapayOrder: ref={}", reference);

        CheckerOrder order = checkerOrderRepository.findByGatewayRef(reference)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Checker order not found for Korapay ref: " + reference));

        order.setStatus(CheckerOrder.CheckerOrderStatus.VERIFIED);
        checkerOrderRepository.save(order);

        log.info("[STOREFRONT] Korapay checker order VERIFIED: orderId={} ref={}", order.getId(), reference);

        try {
            checkerService.provisionFromStock(order);
            notificationService.sendCheckerCredentialsSms(
                    order.getPhoneNumber(), order.getExamType().name(),
                    order.getSerial(), order.getPin(), order.getResultsLink());
            updateResellerCheckerStats(order);
        } catch (UpstreamApiException ex) {
            log.error("[STOREFRONT] Checker provision failed: orderId={} error={}", order.getId(), ex.getMessage());
            if (order.getUser() != null) {
                notificationService.sendCheckerOrderFailedAlert(
                        order.getUser().getEmail(), order.getUser().getFullName(), order.getId());
            }
        }
    }

    // ── Wallet storefront CHECKER order (NEW) ──────────────────────────────────

    @Transactional
    public CheckerOrderResponse placeWalletStorefrontCheckerOrder(String slug, UUID customerId,
                                                                   CheckerWalletRequest request) {
        log.info("[STOREFRONT] placeWalletStorefrontCheckerOrder: slug={} customerId={} phone={} examType={}",
                slug, customerId, request.getPhoneNumber(), request.getExamType());

        User customer = findUserOrThrow(customerId);
        ResellerProfile profile = findApprovedProfileBySlugOrThrow(slug);
        CheckerResellerPricing pricing = checkerResellerPricingRepository
                .findByResellerAndExamType(profile.getUser(), request.getExamType())
                .orElseThrow(() -> new BundleNotFoundException(
                        "Checker not available on this store: examType=" + request.getExamType()));

        BigDecimal sellingPrice = pricing.getSellingPriceGhc();

        walletService.debit(customerId, sellingPrice, TransactionType.PURCHASE,
                request.getExamType() + " checker for " + request.getPhoneNumber()
                        + " via " + profile.getEffectiveStoreName(),
                null);

        CheckerOrder order = CheckerOrder.builder()
                .user(customer)
                .phoneNumber(request.getPhoneNumber())
                .examType(request.getExamType())
                .priceGhc(sellingPrice)
                .paymentMethod(CheckerOrder.PaymentMethod.WALLET)
                .status(CheckerOrder.CheckerOrderStatus.PENDING)
                .guest(false)
                .resellerProfile(profile)
                .storefrontOrder(true)
                .build();

        try {
            order = checkerOrderRepository.save(order);
        } catch (DataIntegrityViolationException ex) {
            walletService.credit(customerId, sellingPrice, TransactionType.REFUND,
                    "Refund: duplicate checker order rejected", null);
            throw new DuplicateOrderException(
                    "A similar checker order was already placed in the last "
                            + DUPLICATE_WINDOW_SECONDS + " seconds.");
        }

        log.info("[STOREFRONT] Wallet checker order placed: slug={} customerId={} orderId={} price={}",
                slug, customerId, order.getId(), sellingPrice);

        try {
            checkerService.provisionFromStock(order);
            notificationService.sendCheckerCredentialsSms(
                    order.getPhoneNumber(), order.getExamType().name(),
                    order.getSerial(), order.getPin(), order.getResultsLink());
            updateResellerCheckerStats(order);
        } catch (UpstreamApiException ex) {
            log.error("[STOREFRONT] Checker provision failed: orderId={} error={}",
                    order.getId(), ex.getMessage());

            walletService.credit(customerId, sellingPrice, TransactionType.REFUND,
                    "Refund: failed checker delivery for order #" + order.getId(), null);

            notificationService.sendCheckerOrderFailedAlert(
                    customer.getEmail(), customer.getFullName(), order.getId());
        }

        CheckerOrder finalOrder = checkerOrderRepository.findById(order.getId()).orElseThrow();
        return CheckerOrderResponse.builder()
                .id(finalOrder.getId())
                .phoneNumber(finalOrder.getPhoneNumber())
                .examType(finalOrder.getExamType().name())
                .priceGhc(finalOrder.getPriceGhc())
                .paymentMethod(finalOrder.getPaymentMethod().name())
                .status(finalOrder.getStatus().name())
                .serial(finalOrder.getStatus() == CheckerOrder.CheckerOrderStatus.COMPLETED ? finalOrder.getSerial() : null)
                .pin(finalOrder.getStatus() == CheckerOrder.CheckerOrderStatus.COMPLETED ? finalOrder.getPin() : null)
                .examDate(finalOrder.getExamDate())
                .resultsLink(finalOrder.getStatus() == CheckerOrder.CheckerOrderStatus.COMPLETED ? finalOrder.getResultsLink() : null)
                .failureReason(finalOrder.getFailureReason())
                .guest(finalOrder.isGuest())
                .createdAt(finalOrder.getCreatedAt())
                .updatedAt(finalOrder.getUpdatedAt())
                .build();
    }

    // ── Reseller stats update ─────────────────────────────────────────────────

    @Transactional
    public void updateResellerStats(Order order) {
        if (order.getResellerProfile() == null) return;

        ResellerProfile profile = resellerProfileRepository
                .findById(order.getResellerProfile().getId())
                .orElse(null);

        if (profile == null) return;

        BigDecimal revenue = order.getSellingPriceGhc();
        BigDecimal cost    = order.getCostPriceGhc();
        BigDecimal profit  = revenue.subtract(cost);

        profile.setTotalRevenueGhc(profile.getTotalRevenueGhc().add(revenue));
        profile.setTotalCostGhc(profile.getTotalCostGhc().add(cost));
        profile.setTotalProfitGhc(profile.getTotalProfitGhc().add(profit));

        resellerProfileRepository.save(profile);

        log.info("[STOREFRONT] Reseller stats updated: profileId={} +revenue={} +cost={} +profit={}",
                profile.getId(), revenue, cost, profit);
    }

    /**
     * Same aggregate update as updateResellerStats(Order), for checker
     * orders. Uses the platform's checker cost price (CheckerPricing) as
     * "cost", exactly mirroring how updateResellerStats(Order) uses
     * order.getCostPriceGhc() (which itself came from PlatformSettings).
     */
    @Transactional
    public void updateResellerCheckerStats(CheckerOrder order) {
        if (order.getResellerProfile() == null) return;

        ResellerProfile profile = resellerProfileRepository
                .findById(order.getResellerProfile().getId())
                .orElse(null);
        if (profile == null) return;

        CheckerPricing platformPricing = checkerPricingRepository
                .findByExamTypeAndActiveTrue(order.getExamType())
                .orElse(null);
        if (platformPricing == null) {
            log.warn("[STOREFRONT] No active CheckerPricing for examType={} — skipping stats update for orderId={}",
                    order.getExamType(), order.getId());
            return;
        }

        BigDecimal revenue = order.getPriceGhc();
        BigDecimal cost    = platformPricing.getResellerPriceGhc();
        BigDecimal profit  = revenue.subtract(cost);

        profile.setTotalRevenueGhc(profile.getTotalRevenueGhc().add(revenue));
        profile.setTotalCostGhc(profile.getTotalCostGhc().add(cost));
        profile.setTotalProfitGhc(profile.getTotalProfitGhc().add(profit));

        resellerProfileRepository.save(profile);

        log.info("[STOREFRONT] Reseller checker stats updated: profileId={} +revenue={} +cost={} +profit={}",
                profile.getId(), revenue, cost, profit);
    }

    // ── Store overview (NEW — "get all the store stuff") ────────────────────────

    /**
     * Combined "everything about this store" view — branding, both
     * product-type price lists, and financial aggregates in one call.
     * Used by the reseller's own dashboard (to manage their store) or by
     * an admin inspecting a store by slug. Unlike getStorefront(), this is
     * NOT meant to be public — it includes revenue/cost/profit figures.
     */
    @Transactional(readOnly = true)
    public StoreOverviewResponse getStoreOverview(String slug) {
        log.info("[STOREFRONT] getStoreOverview: slug={}", slug);

        ResellerProfile profile = findProfileBySlugOrThrow(slug);

        List<StoreOverviewResponse.BundlePriceItem> bundlePricing = resellerPricingRepository
                .findByReseller(profile.getUser()).stream()
                .map(p -> {
                    BigDecimal cost = platformSettingsRepository
                            .findByNetworkAndCapacityGbAndActiveTrue(p.getNetwork(), p.getCapacityGb())
                            .map(PlatformSettings::getResellerPriceGhc)
                            .orElse(null);
                    return StoreOverviewResponse.BundlePriceItem.builder()
                            .network(p.getNetwork().name())
                            .capacityGb(p.getCapacityGb())
                            .costPriceGhc(cost)
                            .sellingPriceGhc(p.getSellingPriceGhc())
                            .build();
                })
                .collect(Collectors.toList());

        List<StoreOverviewResponse.CheckerPriceItem> checkerPricing = checkerResellerPricingRepository
                .findByReseller(profile.getUser()).stream()
                .map(p -> {
                    BigDecimal cost = checkerPricingRepository
                            .findByExamTypeAndActiveTrue(p.getExamType())
                            .map(CheckerPricing::getResellerPriceGhc)
                            .orElse(null);
                    return StoreOverviewResponse.CheckerPriceItem.builder()
                            .examType(p.getExamType().name())
                            .costPriceGhc(cost)
                            .sellingPriceGhc(p.getSellingPriceGhc())
                            .build();
                })
                .collect(Collectors.toList());

        log.info("[STOREFRONT] Store overview: slug={} bundlePriceCount={} checkerPriceCount={}",
                slug, bundlePricing.size(), checkerPricing.size());

        return StoreOverviewResponse.builder()
                .storeSlug(profile.getStoreSlug())
                .storeName(profile.getEffectiveStoreName())
                .storeTagline(profile.getStoreTagline())
                .storeLogoUrl(profile.getStoreLogoUrl())
                .themeColour(profile.getThemeColour())
                .whatsappNumber(profile.getWhatsappNumber())
                .instagramHandle(profile.getInstagramHandle())
                .bannerImageUrl(profile.getBannerImageUrl())
                .welcomeMessage(profile.getWelcomeMessage())
                .buttonStyle(profile.getButtonStyle() != null ? profile.getButtonStyle().name() : null)
                .storeTheme(profile.getStoreTheme() != null ? profile.getStoreTheme().name() : null)
                .status(profile.getStatus().name())
                .bundlePricing(bundlePricing)
                .checkerPricing(checkerPricing)
                .totalRevenueGhc(profile.getTotalRevenueGhc())
                .totalCostGhc(profile.getTotalCostGhc())
                .totalProfitGhc(profile.getTotalProfitGhc())
                .profitPaidGhc(profile.getProfitPaidGhc())
                .availableProfitGhc(profile.getAvailableProfitGhc())
                .build();
    }

    // ── DataPrimo bundle provisioning helper ──────────────────────────────────

    private void provisionOrder(Order order) {
        PlatformSettings settings = findActiveSettingsOrThrow(order.getNetwork(), order.getCapacityGb());

        String productId = settings.getDataprimoProductId();
        String network    = settings.getDataprimoNetwork();

        log.info("[STOREFRONT] provisionOrder: orderId={} network={} capacityGb={} → " +
                        "dataprimoProductId={} dataprimoNetwork={}",
                order.getId(), order.getNetwork(), order.getCapacityGb(), productId, network);

        if (productId == null || productId.isBlank() || network == null || network.isBlank()) {
            log.error("[STOREFRONT] No DataPrimo catalog mapping for orderId={} network={} capacityGb={}",
                    order.getId(), order.getNetwork(), order.getCapacityGb());
            throw new UpstreamApiException(
                    "Bundle network=" + order.getNetwork() + " capacityGb=" + order.getCapacityGb()
                            + " has no DataPrimo catalog mapping — cannot provision orderId=" + order.getId());
        }

        dataPrimoService.purchase(order, productId, network);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildGuestEmail(String phoneNumber) {
        String domain = appConfig.getAppBaseUrl().replaceAll("https?://", "");
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            digits = "guest";
        }
        return digits + "@" + domain;
    }

    private String buildRedirectUrl() {
        // ✅ Now resolved dynamically from the actual calling frontend's
        // Origin/Referer header instead of the static app.base-url config.
        return frontendUrlResolver.resolveBaseUrl() + "/payment/callback";
    }

    private ResellerProfile findProfileBySlugOrThrow(String slug) {
        return resellerProfileRepository.findByStoreSlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found: " + slug));
    }

    private ResellerProfile findApprovedProfileBySlugOrThrow(String slug) {
        ResellerProfile profile = findProfileBySlugOrThrow(slug);
        if (profile.getStatus() != ResellerProfile.ResellerStatus.APPROVED) {
            throw new ResourceNotFoundException("Store not found: " + slug);
        }
        return profile;
    }

    private ResellerPricing findResellerPricingOrThrow(User reseller,
                                                       PlatformSettings.Network network,
                                                       BigDecimal capacityGb) {
        return resellerPricingRepository
                .findByResellerAndNetworkAndCapacityGb(reseller, network, capacityGb)
                .orElseThrow(() -> new BundleNotFoundException(
                        "Bundle not available on this store: network=" + network
                                + " capacityGb=" + capacityGb));
    }

    private PlatformSettings findActiveSettingsOrThrow(PlatformSettings.Network network,
                                                       BigDecimal capacityGb) {
        return platformSettingsRepository
                .findByNetworkAndCapacityGbAndActiveTrue(network, capacityGb)
                .orElseThrow(() -> new BundleNotFoundException(
                        "Bundle not available: network=" + network
                                + " capacityGb=" + capacityGb));
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private OrderResponse toOrderResponse(Order o) {
        return toOrderResponse(o, null);
    }

    private OrderResponse toOrderResponse(Order o, String authorizationUrl) {
        return OrderResponse.builder()
                .id(o.getId())
                .phoneNumber(o.getPhoneNumber())
                .network(o.getNetwork().name())
                .capacityGb(o.getCapacityGb())
                .costPriceGhc(o.getCostPriceGhc())
                .sellingPriceGhc(o.getSellingPriceGhc())
                .paymentMethod(o.getPaymentMethod().name())
                .paystackRef(o.getPaystackRef())
                .authorizationUrl(authorizationUrl)
                .status(o.getStatus().name())
                .guest(o.isGuest())
                .createdAt(o.getCreatedAt())
                .updatedAt(o.getUpdatedAt())
                .build();
    }
}
