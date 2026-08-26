package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.dtos.CheckerWalletRequest;
import com.databundleHum.OnetBundleHub.dtos.InitiateGuestCheckerOrderRequest;
import com.databundleHum.OnetBundleHub.dtos.response.CheckerOrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.InitiateCheckerOrderResponse;
import com.databundleHum.OnetBundleHub.entity.CheckerOrder;
import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import com.databundleHum.OnetBundleHub.entity.User;
import com.databundleHum.OnetBundleHub.entity.WalletTransaction.TransactionType;
import com.databundleHum.OnetBundleHub.repos.CheckerOrderRepository;
import com.databundleHum.OnetBundleHub.repos.CheckerPricingRepository;
import com.databundleHum.OnetBundleHub.repos.UserRepository;
import com.databundleHum.OnetBundleHub.security.BundleNotFoundException;
import com.databundleHum.OnetBundleHub.security.DuplicateOrderException;
import com.databundleHum.OnetBundleHub.security.ResourceNotFoundException;
import com.databundleHum.OnetBundleHub.security.UpstreamApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles result-checker (BECE/WASSCE) purchase flows:
 *  - Guest checkout  (Korapay Checkout Redirect → webhook → DataBossHub provision)
 *  - User wallet purchase (synchronous — no webhook needed)
 *  - Purchase history
 *
 * Mirrors OrderService's structure and conventions (transaction-boundary
 * discipline, 10% Korapay processing charge, guest payer-email pattern) but
 * is a FRESH entity/flow with no legacy Paystack naming — CheckerOrder uses
 * gatewayRef / KORAPAY from day one.
 *
 * ── Why wallet checker purchase is simpler than wallet bundle purchase ────
 * OrderService's placeWalletOrder() must call out to DataPrimo (fire-and-
 * forget, needs a poller to confirm delivery later). DataBossHub's checker
 * purchase is synchronous — buyCheckerSlot() returns credentials or fails
 * in the same call. So purchaseCheckerWallet() below debits the wallet,
 * buys the checker, and returns the result to the caller in one request —
 * no PENDING-then-poll cycle, no @Scheduled job needed for checkers.
 *
 * ── Slot contention retry ──────────────────────────────────────────────────
 * purchaseFromDataBossHub() re-fetches the slot list and tries a different
 * id on failure, up to MAX_SLOT_ATTEMPTS times, since DataBossHub's slots
 * are a shared finite pool that can be claimed by someone else between our
 * fetch and our buy call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckerService {

    private static final int DUPLICATE_WINDOW_SECONDS = 30;
    private static final int MAX_SLOT_ATTEMPTS = 3;

    /** Must match OrderService.PROCESSING_CHARGE_RATE for consistency across products. */
    private static final BigDecimal PROCESSING_CHARGE_RATE = new BigDecimal("0.10");

    private static final String SITE_PREFIX = "databaygh.shop";

    private final CheckerOrderRepository     checkerOrderRepository;
    private final CheckerPricingRepository   checkerPricingRepository;
    private final UserRepository             userRepository;
    private final WalletService              walletService;
    private final KorapayService             korapayService;
    private final DataBossHubService         dataBossHubService;
    private final NotificationService        notificationService;
    private final com.databundleHum.OnetBundleHub.config.AppConfig appConfig;

    // ── Guest checkout: step 1 — initiate ────────────────────────────────────

    @Transactional
    public InitiateCheckerOrderResponse initiateGuestCheckerOrder(InitiateGuestCheckerOrderRequest request) {
        log.info("[CHECKER] initiateGuestCheckerOrder: phone={} examType={}",
                request.getPhoneNumber(), request.getExamType());

        CheckerPricing pricing = getActivePricing(request.getExamType());
        BigDecimal basePriceGhc = pricing.getPublicPriceGhc();
        BigDecimal chargeAmountGhc = addProcessingCharge(basePriceGhc);

        String reference = SITE_PREFIX + "-" + korapayService.generateReference();
        String guestEmail = buildPayerEmail(request.getPhoneNumber());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type",          "CHECKER_ORDER");
        metadata.put("phone",         request.getPhoneNumber());
        metadata.put("examType",      request.getExamType().name());
        metadata.put("baseAmountGhc", basePriceGhc.toPlainString());
        metadata.put("customerName",  request.getPhoneNumber() + " - " + SITE_PREFIX);

        Map<String, Object> korapayData = korapayService.initiateTransaction(
                guestEmail,
                request.getPhoneNumber(),
                chargeAmountGhc,
                reference,
                buildRedirectUrl(),
                metadata
        );

        CheckerOrder order = CheckerOrder.builder()
                .phoneNumber(request.getPhoneNumber())
                .examType(request.getExamType())
                .priceGhc(basePriceGhc)
                .paymentMethod(CheckerOrder.PaymentMethod.KORAPAY)
                .gatewayRef(reference)
                .status(CheckerOrder.CheckerOrderStatus.PENDING)
                .guest(true)
                .storefrontOrder(false)
                .build();
        checkerOrderRepository.save(order);

        log.info("[CHECKER] Guest checker order initiated: orderId={} ref={} phone={} examType={} " +
                        "basePrice={} chargeAmount={}",
                order.getId(), reference, request.getPhoneNumber(), request.getExamType(),
                basePriceGhc, chargeAmountGhc);

        return InitiateCheckerOrderResponse.builder()
                .gatewayRef(reference)
                .checkoutUrl((String) korapayData.get("checkout_url"))
                .amountGhc(chargeAmountGhc)
                .phoneNumber(request.getPhoneNumber())
                .examType(request.getExamType().name())
                .build();
    }

    // ── Guest checkout: step 2 — webhook fulfilment ───────────────────────────

    /**
     * Called by WebhookController/WebhookService after Korapay payment is
     * verified for a CHECKER_ORDER metadata type.
     */
    public void fulfilCheckerKorapayOrder(String reference) {
        log.info("[CHECKER] fulfilCheckerKorapayOrder: ref={}", reference);

        CheckerOrder order = markCheckerOrderVerified(reference);

        try {
            purchaseFromDataBossHub(order);
            deliverCredentials(order);
        } catch (UpstreamApiException ex) {
            log.error("[CHECKER] DataBossHub provision failed after Korapay payment: orderId={} ref={} error={}",
                    order.getId(), reference, ex.getMessage());
            markCheckerOrderFailed(order.getId(), ex.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected CheckerOrder markCheckerOrderVerified(String reference) {
        CheckerOrder order = checkerOrderRepository.findByGatewayRef(reference)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Checker order not found for ref: " + reference));

        order.setStatus(CheckerOrder.CheckerOrderStatus.VERIFIED);
        checkerOrderRepository.save(order);

        log.info("[CHECKER] Checker order VERIFIED: orderId={} ref={}", order.getId(), reference);
        return order;
    }

    // ── Wallet purchase (synchronous) ─────────────────────────────────────────

    /**
     * Wallet-funded checker purchase. Synchronous end to end: debit wallet,
     * buy from DataBossHub, return credentials — or refund and fail — all
     * within this one call. No processing charge (already collected at
     * top-up time, same rule as OrderService.placeWalletOrder).
     */
    public CheckerOrderResponse purchaseCheckerWallet(UUID userId, CheckerWalletRequest request) {
        log.info("[CHECKER] purchaseCheckerWallet: userId={} phone={} examType={}",
                userId, request.getPhoneNumber(), request.getExamType());

        User user = findUserOrThrow(userId);
        CheckerPricing pricing = getActivePricing(request.getExamType());
        BigDecimal price = pricing.getPublicPriceGhc();

        rejectIfDuplicate(userId, request.getPhoneNumber(), request.getExamType());

        walletService.debit(userId, price, TransactionType.PURCHASE,
                request.getExamType() + " checker for " + request.getPhoneNumber(), null);

        CheckerOrder order;
        try {
            order = saveNewCheckerOrder(CheckerOrder.builder()
                    .user(user)
                    .phoneNumber(request.getPhoneNumber())
                    .examType(request.getExamType())
                    .priceGhc(price)
                    .paymentMethod(CheckerOrder.PaymentMethod.WALLET)
                    .status(CheckerOrder.CheckerOrderStatus.PENDING)
                    .guest(false)
                    .storefrontOrder(false)
                    .idempotencyKey(buildIdempotencyKey(userId, request.getPhoneNumber(), request.getExamType()))
                    .build());
        } catch (DataIntegrityViolationException ex) {
            log.warn("[CHECKER] Duplicate wallet checker order blocked: userId={} phone={} examType={}",
                    userId, request.getPhoneNumber(), request.getExamType());
            walletService.credit(userId, price, TransactionType.REFUND,
                    "Refund: duplicate checker order rejected", null);
            throw new DuplicateOrderException(
                    "A similar checker order was already placed in the last "
                            + DUPLICATE_WINDOW_SECONDS + " seconds.");
        }

        try {
            purchaseFromDataBossHub(order);
            deliverCredentials(order);
        } catch (UpstreamApiException ex) {
            handleWalletProvisioningFailure(order.getId(), user, price, ex);
        }

        return toResponse(checkerOrderRepository.findById(order.getId()).orElseThrow());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected CheckerOrder saveNewCheckerOrder(CheckerOrder order) {
        return checkerOrderRepository.save(order);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void handleWalletProvisioningFailure(Long orderId, User user, BigDecimal price, UpstreamApiException ex) {
        markCheckerOrderFailed(orderId, ex.getMessage());
        walletService.credit(user.getId(), price, TransactionType.REFUND,
                "Refund: failed checker delivery for order #" + orderId, null);
        notificationService.sendCheckerOrderFailedAlert(user.getEmail(), user.getFullName(), orderId);

        log.error("[CHECKER] Provisioning failed, wallet refunded: orderId={} userId={} amount={} error={}",
                orderId, user.getId(), price, ex.getMessage());
    }

    // ── Shared DataBossHub provisioning (used by guest, wallet, storefront) ──

    /**
     * Buys a checker from DataBossHub for this order's exam type, retrying
     * against a freshly re-fetched slot list on contention, and persists
     * the result onto the order (COMPLETED with credentials, or throws).
     *
     * Public so ResellerStorefrontService can reuse this exact logic for
     * storefront checker orders, mirroring how it calls
     * dataPrimoService.purchase(order, productId, network) for bundles.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purchaseFromDataBossHub(CheckerOrder order) {
        log.info("[CHECKER] purchaseFromDataBossHub: orderId={} examType={}", order.getId(), order.getExamType());

        CheckerPricing pricing = getActivePricing(order.getExamType());
        String category = pricing.getDataBossHubCategory();
        if (category == null || category.isBlank()) {
            log.error("[CHECKER] No DataBossHub category configured for examType={} — orderId={}",
                    order.getExamType(), order.getId());
            throw new UpstreamApiException(
                    "CheckerPricing for examType=" + order.getExamType()
                            + " has no dataBossHubCategory configured — cannot provision orderId=" + order.getId());
        }

        UpstreamApiException lastException = null;

        for (int attempt = 1; attempt <= MAX_SLOT_ATTEMPTS; attempt++) {
            log.info("[CHECKER] Slot attempt {}/{} — orderId={} category={}",
                    attempt, MAX_SLOT_ATTEMPTS, order.getId(), category);

            List<Map<String, Object>> slots = dataBossHubService.fetchAvailableSlots(category);
            if (slots.isEmpty()) {
                log.error("[CHECKER] No available DataBossHub slots for category={} — orderId={}",
                        category, order.getId());
                throw new UpstreamApiException(
                        "No available checker slots for category=" + category
                                + " — orderId=" + order.getId());
            }

            String slotId = dataBossHubService.extractSlotId(slots.get(0));
            if (slotId == null) {
                log.error("[CHECKER] Could not extract slot id from DataBossHub response — orderId={}",
                        order.getId());
                throw new UpstreamApiException(
                        "DataBossHub slot list entry had no extractable id — orderId=" + order.getId());
            }

            try {
                Map<String, Object> credentialsRaw = dataBossHubService.buyCheckerSlot(slotId);
                DataBossHubService.CheckerCredentials creds =
                        dataBossHubService.extractCheckerFields(credentialsRaw);

                if (creds.serial() == null || creds.pin() == null) {
                    throw new UpstreamApiException(
                            "DataBossHub buy response missing serial/pin for slotId=" + slotId
                                    + " orderId=" + order.getId());
                }

                order.setDataBossHubSlotId(slotId);
                order.setSerial(creds.serial());
                order.setPin(creds.pin());
                order.setExamDate(creds.examDate());
                order.setResultsLink(creds.resultsLink());
                order.setStatus(CheckerOrder.CheckerOrderStatus.COMPLETED);
                checkerOrderRepository.save(order);

                log.info("[CHECKER] ✔ Checker purchased and order COMPLETED: orderId={} slotId={}",
                        order.getId(), slotId);
                return;

            } catch (UpstreamApiException ex) {
                lastException = ex;
                log.warn("[CHECKER] Slot purchase failed on attempt {}/{} (likely contention) — " +
                                "orderId={} slotId={} error={}",
                        attempt, MAX_SLOT_ATTEMPTS, order.getId(), slotId, ex.getMessage());
            }
        }

        log.error("[CHECKER] All {} slot attempts exhausted — orderId={}", MAX_SLOT_ATTEMPTS, order.getId());
        markCheckerOrderFailed(order.getId(),
                lastException != null ? lastException.getMessage() : "All slot attempts exhausted");
        throw new UpstreamApiException(
                "Checker purchase failed after " + MAX_SLOT_ATTEMPTS + " slot attempts. OrderId=" + order.getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markCheckerOrderFailed(Long orderId, String reason) {
        CheckerOrder order = checkerOrderRepository.findById(orderId).orElseThrow();
        order.setStatus(CheckerOrder.CheckerOrderStatus.FAILED);
        order.setFailureReason(reason);
        checkerOrderRepository.save(order);

        log.warn("[CHECKER] Checker order marked FAILED: orderId={} reason={}", orderId, reason);

        if (order.getUser() != null) {
            notificationService.sendCheckerOrderFailedAlert(
                    order.getUser().getEmail(), order.getUser().getFullName(), orderId);
        }
    }

    /** On-screen delivery is just returning the order (already has credentials); this handles the SMS half. */
    private void deliverCredentials(CheckerOrder order) {
        notificationService.sendCheckerCredentialsSms(
                order.getPhoneNumber(),
                order.getExamType().name(),
                order.getSerial(),
                order.getPin(),
                order.getResultsLink()
        );
        log.info("[CHECKER] Credentials delivery triggered (SMS + on-screen + stored history) — orderId={}",
                order.getId());
    }

    // ── History / status queries ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CheckerOrderResponse> getCheckerHistory(UUID userId, Pageable pageable) {
        User user = findUserOrThrow(userId);
        return checkerOrderRepository.findByUserOrderByCreatedAtDesc(user, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CheckerOrderResponse getCheckerOrderStatusByRef(String reference) {
        CheckerOrder order = checkerOrderRepository.findByGatewayRef(reference)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Checker order not found for ref: " + reference));
        return toResponse(order);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private CheckerPricing getActivePricing(CheckerPricing.ExamType examType) {
        return checkerPricingRepository.findByExamTypeAndActiveTrue(examType)
                .orElseThrow(() -> new BundleNotFoundException(
                        "Checker not available for exam type: " + examType));
    }

    private String buildPayerEmail(String phoneNumber) {
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            digits = "guest";
        }
        return digits + "@" + SITE_PREFIX;
    }

    private String buildRedirectUrl() {
        return appConfig.getAppBaseUrl() + "/payment/callback";
    }

    private void rejectIfDuplicate(UUID userId, String phoneNumber, CheckerPricing.ExamType examType) {
        LocalDateTime windowStart = LocalDateTime.now().minusSeconds(DUPLICATE_WINDOW_SECONDS);

        boolean duplicate = checkerOrderRepository
                .existsByUserIdAndPhoneNumberAndExamTypeAndStatusNotAndCreatedAtAfter(
                        userId, phoneNumber, examType, CheckerOrder.CheckerOrderStatus.FAILED, windowStart);

        if (duplicate) {
            log.warn("[CHECKER] Duplicate rejected: userId={} phone={} examType={} window={}s",
                    userId, phoneNumber, examType, DUPLICATE_WINDOW_SECONDS);
            throw new DuplicateOrderException(
                    "A similar checker order was already placed in the last "
                            + DUPLICATE_WINDOW_SECONDS + " seconds.");
        }
    }

    private String buildIdempotencyKey(UUID userId, String phoneNumber, CheckerPricing.ExamType examType) {
        long bucket = System.currentTimeMillis() / 1000L / DUPLICATE_WINDOW_SECONDS;
        return userId + ":" + phoneNumber + ":" + examType.name() + ":" + bucket;
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private BigDecimal addProcessingCharge(BigDecimal baseAmountGhc) {
        return baseAmountGhc
                .multiply(BigDecimal.ONE.add(PROCESSING_CHARGE_RATE))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    private CheckerOrderResponse toResponse(CheckerOrder o) {
        return CheckerOrderResponse.builder()
                .id(o.getId())
                .phoneNumber(o.getPhoneNumber())
                .examType(o.getExamType().name())
                .priceGhc(o.getPriceGhc())
                .paymentMethod(o.getPaymentMethod().name())
                .gatewayRef(o.getGatewayRef())
                .status(o.getStatus().name())
                .serial(o.getStatus() == CheckerOrder.CheckerOrderStatus.COMPLETED ? o.getSerial() : null)
                .pin(o.getStatus() == CheckerOrder.CheckerOrderStatus.COMPLETED ? o.getPin() : null)
                .examDate(o.getExamDate())
                .resultsLink(o.getStatus() == CheckerOrder.CheckerOrderStatus.COMPLETED ? o.getResultsLink() : null)
                .failureReason(o.getFailureReason())
                .guest(o.isGuest())
                .createdAt(o.getCreatedAt())
                .updatedAt(o.getUpdatedAt())
                .build();
    }
}
