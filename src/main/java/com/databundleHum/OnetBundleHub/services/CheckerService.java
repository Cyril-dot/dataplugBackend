package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.dtos.CheckerWalletRequest;
import com.databundleHum.OnetBundleHub.dtos.InitiateGuestCheckerOrderRequest;
import com.databundleHum.OnetBundleHub.dtos.response.CheckerOrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.CheckerPublicPricingResponse;
import com.databundleHum.OnetBundleHub.dtos.response.InitiateCheckerOrderResponse;
import com.databundleHum.OnetBundleHub.entity.CheckerOrder;
import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import com.databundleHum.OnetBundleHub.entity.CheckerStock;
import com.databundleHum.OnetBundleHub.entity.User;
import com.databundleHum.OnetBundleHub.entity.WalletTransaction.TransactionType;
import com.databundleHum.OnetBundleHub.repos.CheckerOrderRepository;
import com.databundleHum.OnetBundleHub.repos.CheckerPricingRepository;
import com.databundleHum.OnetBundleHub.repos.CheckerStockRepository;
import com.databundleHum.OnetBundleHub.repos.UserRepository;
import com.databundleHum.OnetBundleHub.security.BundleNotFoundException;
import com.databundleHum.OnetBundleHub.security.ConflictException;
import com.databundleHum.OnetBundleHub.security.DuplicateOrderException;
import com.databundleHum.OnetBundleHub.security.ResourceNotFoundException;
import com.databundleHum.OnetBundleHub.security.UpstreamApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
 *  - Guest checkout  (Korapay Checkout Redirect → webhook → stock provision)
 *  - User wallet purchase (synchronous — no webhook needed)
 *  - Purchase history
 *
 * Mirrors OrderService's structure and conventions (transaction-boundary
 * discipline, 10% Korapay processing charge, guest payer-email pattern) but
 * is a FRESH entity/flow with no legacy Paystack naming — CheckerOrder uses
 * gatewayRef / KORAPAY from day one.
 *
 * ── Stock model (replaces the old live DataBossHub purchase) ────────────
 * Checker purchase used to call DataBossHub live, claiming one of its
 * finite numbered slots at the moment a customer paid. That's been
 * replaced with an admin-managed stock model: the Super Admin pre-acquires
 * a batch of checker credentials — manually pasted in, or bought in bulk
 * via DataBossHub or Big Dreams Data through AdminCheckerStockController —
 * and provisionFromStock() below just claims the next unused row from our
 * own database. No live upstream call happens at customer-purchase time
 * any more; if stock is empty, the purchase fails immediately with a clear
 * "out of stock" error instead of hitting a third-party API.
 *
 * ── Why wallet checker purchase is simpler than wallet bundle purchase ────
 * OrderService's placeWalletOrder() must call out to DataPrimo (fire-and-
 * forget, needs a poller to confirm delivery later). Stock provisioning is
 * synchronous and local — purchaseCheckerWallet() below debits the wallet,
 * claims a stock row, and returns the result in one request — no
 * PENDING-then-poll cycle, no @Scheduled job needed for checkers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckerService {

    private static final int DUPLICATE_WINDOW_SECONDS = 30;

    /** Must match OrderService.PROCESSING_CHARGE_RATE for consistency across products. */
    private static final BigDecimal PROCESSING_CHARGE_RATE = new BigDecimal("0.10");

    private static final String SITE_PREFIX = "databaygh.shop";

    // ✅ FIXED — confirmed via live Railway logs: Korapay rejects any
    // reference containing a period with a 422 ("reference must only
    // contain alphanumeric, hyphen and underscore characters"). SITE_PREFIX
    // itself must keep its dot (it's also used to build the payer email
    // domain, e.g. "0244...@databaygh.shop", where a dot is required) —
    // this is a separate, sanitized value used ONLY when building the
    // reference string sent to Korapay.
    private static final String REFERENCE_PREFIX = "databaygh-shop";

    private final CheckerOrderRepository     checkerOrderRepository;
    private final CheckerPricingRepository   checkerPricingRepository;
    private final CheckerStockRepository     checkerStockRepository;
    private final UserRepository             userRepository;
    private final WalletService              walletService;
    private final KorapayService             korapayService;
    private final NotificationService        notificationService;
    private final com.databundleHum.OnetBundleHub.config.AppConfig appConfig;
    private final com.databundleHum.OnetBundleHub.util.FrontendUrlResolver frontendUrlResolver;

    // ── Guest checkout: step 1 — initiate ────────────────────────────────────

    @Transactional
    public InitiateCheckerOrderResponse initiateGuestCheckerOrder(InitiateGuestCheckerOrderRequest request) {
        log.info("[CHECKER] initiateGuestCheckerOrder: phone={} examType={}",
                request.getPhoneNumber(), request.getExamType());

        CheckerPricing pricing = getActivePricing(request.getExamType());
        BigDecimal basePriceGhc = pricing.getPublicPriceGhc();
        BigDecimal chargeAmountGhc = addProcessingCharge(basePriceGhc);

        String reference = REFERENCE_PREFIX + "-" + korapayService.generateReference();
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
            provisionFromStock(order);
        } catch (UpstreamApiException ex) {
            // provisionFromStock already marks FAILED itself for the out-of-stock
            // case before throwing, so this branch is just for logging.
            log.error("[CHECKER] Stock provisioning failed after Korapay payment: orderId={} ref={} error={}",
                    order.getId(), reference, ex.getMessage());
            return;
        } catch (Exception ex) {
            // ── FIX: previously only UpstreamApiException was caught here, so any
            // other failure (DB error, constraint violation, NPE, etc.) propagated
            // out uncaught. WebhookController's outer try/catch swallows it and
            // logs — but by then the order is stuck at VERIFIED forever, with no
            // scheduled job to reconcile it. The customer had already paid and the
            // frontend shows VERIFIED as "Pending" indefinitely. Now any failure
            // here is caught and the order is explicitly marked FAILED so support/
            // refund flows can pick it up instead of it silently stalling.
            log.error("[CHECKER] Unexpected error provisioning checker after Korapay payment: orderId={} ref={} error={}",
                    order.getId(), reference, ex.getMessage(), ex);
            markCheckerOrderFailed(order.getId(), "Unexpected error while provisioning: " + ex.getMessage());
            return;
        }

        // ── FIX: credential delivery (SMS) is now outside the failure path for
        // provisioning. The order is already COMPLETED at this point (set inside
        // provisionFromStock's own transaction) — an SMS failure here must NOT
        // flip a successfully-provisioned order back to FAILED, since the
        // customer already has valid credentials in their order history/on
        // screen. We just log the SMS failure so it can be retried/investigated
        // without corrupting the order's true fulfilment status.
        try {
            deliverCredentials(order);
        } catch (Exception ex) {
            log.error("[CHECKER] Order provisioned but SMS delivery failed: orderId={} ref={} error={}",
                    order.getId(), reference, ex.getMessage(), ex);
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

    // ── Recovery: stuck (VERIFIED-but-never-provisioned) orders ────────────────

    /**
     * Any order sitting at VERIFIED means the customer's payment cleared but
     * provisionFromStock() either never ran to completion or threw something
     * that (before the exception-handling fix above) escaped uncaught. Used
     * by both the admin manual-retry endpoint and the scheduled sweep below.
     */
    @Transactional(readOnly = true)
    public List<CheckerOrder> findStuckOrders() {
        return checkerOrderRepository.findByStatusOrderByCreatedAtAsc(CheckerOrder.CheckerOrderStatus.VERIFIED);
    }

    /**
     * Re-attempts provisioning for one stuck order. Safe to call repeatedly —
     * if stock is still empty it just re-fails with the same "out of stock"
     * reason each time; once stock exists it completes on the next attempt.
     * Called by AdminCheckerStockController's manual "retry" endpoint and by
     * the scheduled sweep.
     */
    public CheckerOrderResponse retryStuckOrder(Long orderId) {
        CheckerOrder order = checkerOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Checker order not found: " + orderId));

        if (order.getStatus() != CheckerOrder.CheckerOrderStatus.VERIFIED) {
            throw new ConflictException(
                    "Order " + orderId + " is " + order.getStatus() + ", not VERIFIED — nothing to retry."
                            + " Only orders stuck between payment and provisioning can be retried here.");
        }

        log.info("[CHECKER] Manual retry requested for stuck order: orderId={}", orderId);

        try {
            provisionFromStock(order);
        } catch (UpstreamApiException ex) {
            log.warn("[CHECKER] Retry still failed (likely still out of stock): orderId={} error={}",
                    orderId, ex.getMessage());
            return toResponse(checkerOrderRepository.findById(orderId).orElseThrow());
        } catch (Exception ex) {
            log.error("[CHECKER] Retry failed unexpectedly: orderId={} error={}", orderId, ex.getMessage(), ex);
            markCheckerOrderFailed(orderId, "Retry failed: " + ex.getMessage());
            return toResponse(checkerOrderRepository.findById(orderId).orElseThrow());
        }

        CheckerOrder refreshed = checkerOrderRepository.findById(orderId).orElseThrow();
        try {
            deliverCredentials(refreshed);
        } catch (Exception ex) {
            log.error("[CHECKER] Order provisioned on retry but SMS delivery failed: orderId={} error={}",
                    orderId, ex.getMessage(), ex);
        }
        return toResponse(refreshed);
    }

    /**
     * Manually forces a specific order to COMPLETED using a specific stock
     * row the admin already has in hand (e.g. a code bought manually outside
     * the automated stock system, or one they're pasting in on the spot to
     * fulfil a customer who's been waiting). Bypasses findAvailableForUpdate
     * entirely — the admin is choosing the exact code, not drawing the next
     * one off the queue.
     */
    @Transactional
    public CheckerOrderResponse manuallyCompleteOrder(Long orderId, String serial, String pin,
                                                        String examDate, String resultsLink) {
        CheckerOrder order = checkerOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Checker order not found: " + orderId));

        if (order.getStatus() == CheckerOrder.CheckerOrderStatus.COMPLETED) {
            throw new ConflictException("Order " + orderId + " is already COMPLETED.");
        }

        order.setSerial(serial);
        order.setPin(pin);
        order.setExamDate(examDate);
        order.setResultsLink(resultsLink);
        order.setStatus(CheckerOrder.CheckerOrderStatus.COMPLETED);
        order.setFailureReason(null);
        checkerOrderRepository.save(order);

        log.info("[CHECKER] ✔ Manually completed by admin: orderId={} serial={}", orderId, serial);

        try {
            deliverCredentials(order);
        } catch (Exception ex) {
            log.error("[CHECKER] Manually completed but SMS delivery failed: orderId={} error={}",
                    orderId, ex.getMessage(), ex);
        }
        return toResponse(order);
    }

    /**
     * Runs every 5 minutes. Sweeps up any order stuck at VERIFIED for more
     * than 2 minutes (giving the normal synchronous webhook path plenty of
     * time to finish first) and retries provisioning automatically. This is
     * the safety net that didn't exist before — previously a stuck order
     * stayed stuck forever with nothing coming back to check on it.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5 * 60 * 1000)
    public void reconcileStuckOrders() {
        List<CheckerOrder> stuck = findStuckOrders();
        if (stuck.isEmpty()) return;

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(2);
        List<CheckerOrder> readyToRetry = stuck.stream()
                .filter(o -> o.getCreatedAt().isBefore(cutoff))
                .toList();

        if (readyToRetry.isEmpty()) return;

        log.info("[CHECKER] Reconciliation sweep: {} order(s) stuck at VERIFIED, retrying", readyToRetry.size());
        for (CheckerOrder order : readyToRetry) {
            try {
                retryStuckOrder(order.getId());
            } catch (Exception ex) {
                log.error("[CHECKER] Reconciliation retry failed: orderId={} error={}",
                        order.getId(), ex.getMessage(), ex);
            }
        }
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
            provisionFromStock(order);
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

    // ── Shared stock provisioning (used by guest, wallet, storefront) ────────

    /**
     * Claims the oldest unused CheckerStock row for this order's exam type
     * (pessimistic row lock — see CheckerStockRepository.findAvailableForUpdate),
     * marks it consumed, and copies its credentials onto the order as
     * COMPLETED. Throws UpstreamApiException if stock is empty — same
     * exception type the old DataBossHub path used, so every existing
     * catch site (guest webhook fulfilment, wallet purchase, storefront
     * checker orders) keeps working unchanged, including wallet refund
     * logic on failure.
     *
     * Public so ResellerStorefrontService can reuse this exact logic for
     * storefront checker orders, mirroring how it calls
     * dataPrimoService.purchase(order, productId, network) for bundles.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provisionFromStock(CheckerOrder order) {
        log.info("[CHECKER] provisionFromStock: orderId={} examType={}", order.getId(), order.getExamType());

        List<CheckerStock> available = checkerStockRepository.findAvailableForUpdate(
                order.getExamType(), PageRequest.of(0, 1));

        if (available.isEmpty()) {
            log.error("[CHECKER] Out of stock — no available checker codes for examType={} — orderId={}",
                    order.getExamType(), order.getId());
            markCheckerOrderFailed(order.getId(),
                    "Out of stock — no checker codes available for " + order.getExamType()
                            + ". An admin needs to restock.");
            throw new UpstreamApiException(
                    "No checker stock available for examType=" + order.getExamType()
                            + " — orderId=" + order.getId());
        }

        CheckerStock stock = available.get(0);
        stock.setUsed(true);
        stock.setUsedAt(LocalDateTime.now());
        stock.setCheckerOrderId(order.getId());
        checkerStockRepository.save(stock);

        order.setSerial(stock.getSerial());
        order.setPin(stock.getPin());
        order.setExamDate(stock.getExamDate());
        order.setResultsLink(stock.getResultsLink());
        order.setStatus(CheckerOrder.CheckerOrderStatus.COMPLETED);
        checkerOrderRepository.save(order);

        log.info("[CHECKER] ✔ Provisioned from stock: orderId={} stockId={} source={}",
                order.getId(), stock.getId(), stock.getSource());
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

    // ── Public pricing (no auth) ────────────────────────────────────────────────

    /**
     * Public-safe checker pricing for every active exam type — the
     * customer-facing equivalent of PricingService.getPublicPricing() for
     * bundles. Deliberately excludes resellerPriceGhc and
     * dataBossHubCategory (see CheckerPublicPricingResponse's Javadoc).
     */
    @Transactional(readOnly = true)
    public List<CheckerPublicPricingResponse> getPublicPricing() {
        return checkerPricingRepository.findByActiveTrue().stream()
                .map(p -> CheckerPublicPricingResponse.builder()
                        .examType(p.getExamType().name())
                        .publicPriceGhc(p.getPublicPriceGhc())
                        .build())
                .toList();
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
        // ✅ Now resolved dynamically from the actual calling frontend's
        // Origin/Referer header (see FrontendUrlResolver) instead of the
        // static app.base-url config, which requires remembering to update
        // it every time the frontend's domain changes.
        return frontendUrlResolver.resolveBaseUrl() + "/payment/callback";
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
