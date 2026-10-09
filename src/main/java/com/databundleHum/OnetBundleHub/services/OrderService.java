package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.config.AppConfig;
import com.databundleHum.OnetBundleHub.util.DataPackBranding;
import com.databundleHum.OnetBundleHub.util.FrontendUrlResolver;
import com.databundleHum.OnetBundleHub.dtos.InitiateGuestOrderRequest;
import com.databundleHum.OnetBundleHub.dtos.TopUpInitiateRequest;
import com.databundleHum.OnetBundleHub.dtos.*;
import com.databundleHum.OnetBundleHub.dtos.response.InitiateOrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.OrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.TopUpInitiateResponse;
import com.databundleHum.OnetBundleHub.dtos.response.WalletResponse;
import com.databundleHum.OnetBundleHub.dtos.response.RecipientVerificationResponse;
import com.databundleHum.OnetBundleHub.entity.Order;
import com.databundleHum.OnetBundleHub.entity.PlatformSettings;
import com.databundleHum.OnetBundleHub.entity.ProcessedRef;
import com.databundleHum.OnetBundleHub.entity.User;
import com.databundleHum.OnetBundleHub.entity.WalletTransaction.TransactionType;
import com.databundleHum.OnetBundleHub.repos.OrderRepository;
import com.databundleHum.OnetBundleHub.repos.PlatformSettingsRepository;
import com.databundleHum.OnetBundleHub.repos.ProcessedRefRepository;
import com.databundleHum.OnetBundleHub.repos.UserRepository;
import com.databundleHum.OnetBundleHub.repos.WalletTopUpRepository;
import com.databundleHum.OnetBundleHub.entity.WalletTopUp;
import com.databundleHum.OnetBundleHub.security.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
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
 * Handles all order flows:
 *  - Guest checkout  (Korapay Checkout Redirect → webhook → Big Dreams provision)
 *  - User wallet purchase
 *  - Reseller wallet purchase (wholesale price)
 *  - Order status queries
 *
 * ── TRANSACTION-BOUNDARY FIX (2026-07-09) ────────────────────────────────────
 * No flow below holds an open transaction across an external HTTP call; each
 * DB write is its own short REQUIRES_NEW transaction, and upstream calls run
 * with no transaction open on the calling thread — prevents idle-connection
 * timeouts from rolling back already-committed order rows.
 *
 * ── PROCESSING CHARGE (2026-07-10) ───────────────────────────────────────────
 * A 10% processing charge is passed on to the customer at the exact moment
 * real money moves through Korapay (guest checkout, wallet top-up). Wallet-
 * funded order placement is untouched — that 10% was already collected at
 * top-up time.
 *
 * ── MIGRATION FROM PAYSTACK TO KORAPAY (2026-08-26) ──────────────────────────
 * Runs entirely on PaystackService now. Key differences from Paystack:
 *   - Amounts are in GHS directly, not pesewas — no toSmallestUnit() calls.
 *   - initiateTransaction() returns "checkout_url" not "authorization_url"
 *     (DTO field name authorizationUrl kept for compatibility, holds the
 *     Korapay checkout_url now).
 *   - Requires a customerName param Paystack never needed.
 *   - Needs an explicit redirectUrl (Checkout Redirect flow).
 *   - Order.paystackRef / PAYSTACK enum constant are UNCHANGED — renaming
 *     the DB column/enum is a separate migration (TODO below).
 *
 * ── MIGRATION FROM BIG DREAMS TO DATAPRIMO (2026-08-26) ──────────────────────
 * bigDreamsService.purchase(order) → provisionOrder(order), which resolves
 * this bundle's DataPrimo productId/network from PlatformSettings and calls
 * dataPrimoService.purchase(order, productId, network). Delivery confirmation
 * (PENDING → COMPLETED) now happens via DataPrimoService.checkDeliveryStatus(),
 * a @Scheduled poller hitting GET /orders/{id} per pending order — see that
 * class's Javadoc for the full lifecycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final int DUPLICATE_WINDOW_SECONDS = 30;

    /** 10% processing charge passed on to the customer at the point of payment. */
    private static final BigDecimal PROCESSING_CHARGE_RATE = new BigDecimal("0.10");

    /**
     * Bare site domain (no scheme, no "www.") used to prefix Korapay references,
     * build payer email addresses and label customers in metadata.
     */
    private static final String SITE_PREFIX = "datapackk.shop";

    // ✅ FIXED — same bug confirmed live on the Korapay checker path
    // (Railway logs: 422 "reference must only contain alphanumeric, hyphen
    // and underscore characters"). Paystack references may or may not
    // enforce the same restriction, but there's no reason to risk it —
    // this sanitized prefix (no dot) is now used for every reference built
    // here, while SITE_PREFIX (with its dot) stays as-is for the payer
    // email domain and free-text metadata, where a dot is fine/required.
    private static final String REFERENCE_PREFIX = "datapackk-shop";

    private final OrderRepository             orderRepository;
    private final UserRepository              userRepository;
    private final PlatformSettingsRepository  platformSettingsRepository;
    private final ProcessedRefRepository      processedRefRepository;
    private final WalletTopUpRepository       walletTopUpRepository;
    private final WalletService               walletService;
    private final PaystackService              paystackService;
    private final BigDreamsDataService          bigDreamsDataService;
    private final NotificationService         notificationService;
    private final UnverifiedRecipientService  unverifiedRecipientService;
    private final AffiliateCommissionService  affiliateCommissionService;
    private final AppConfig                   appConfig;
    private final FrontendUrlResolver          frontendUrlResolver;
    private final PricingService pricingService;

    public RecipientVerificationResponse checkRecipientVerification(RecipientVerificationRequest request) {
        return unverifiedRecipientService.check(request.getPhoneNumber(), request.getNetwork());
    }

    @Transactional
    public void updateKorapayRefundStatus(String refundReference, String status) {
        orderRepository.findByKorapayRefundReference(refundReference).ifPresentOrElse(order -> {
            order.setKorapayRefundStatus(status == null ? "unknown" : status.toLowerCase());
            if ("success".equalsIgnoreCase(status)) {
                order.setKorapayRefundFailure(null);
            } else if ("failed".equalsIgnoreCase(status)) {
                order.setKorapayRefundFailure("Korapay reported that the refund failed.");
                alertAdminsOfRefundFailure(order, refundReference, order.getKorapayRefundFailure());
            }
            orderRepository.save(order);
            log.info("[KORAPAY] Refund status updated: orderId={} refundReference={} status={}",
                    order.getId(), refundReference, status);
        }, () -> log.warn("[KORAPAY] Refund callback did not match an order: refundReference={} status={}",
                refundReference, status));
    }

    // ── Guest checkout: step 1 — initiate ────────────────────────────────────

    @Transactional
    public InitiateOrderResponse initiateGuestOrder(InitiateGuestOrderRequest request) {
        log.info("[ORDER] initiateGuestOrder: phone={} network={} gb={}",
                request.getPhoneNumber(), request.getNetwork(), request.getCapacityGb());

        PlatformSettings settings = getActiveSettings(
                request.getNetwork(), request.getCapacityGb());

        BigDecimal basePriceGhc = settings.getPublicPriceGhc();
        BigDecimal chargeAmountGhc = addProcessingCharge(basePriceGhc);

        String reference  = REFERENCE_PREFIX + "-" + paystackService.generateReference();
        String guestEmail = buildPayerEmail(request.getPhoneNumber());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type",         "GUEST_ORDER");
        metadata.put("phone",        request.getPhoneNumber());
        metadata.put("network",      request.getNetwork().name());
        metadata.put("capacityGb",   request.getCapacityGb().toString());
        metadata.put("baseAmountGhc", basePriceGhc.toPlainString());
        metadata.put("customerName", request.getPhoneNumber() + " - " + SITE_PREFIX);

        Map<String, Object> paystackData = paystackService.initiateTransaction(
                guestEmail,
                request.getPhoneNumber(),
                chargeAmountGhc,
                reference,
                buildRedirectUrl(),
                metadata
        );

        Order order = Order.builder()
                .phoneNumber(request.getPhoneNumber())
                .network(request.getNetwork())
                .capacityGb(request.getCapacityGb())
                .costPriceGhc(basePriceGhc)
                .sellingPriceGhc(basePriceGhc)
                .paymentMethod(Order.PaymentMethod.PAYSTACK) // TODO: rename enum constant to KORAPAY in a follow-up migration
                .paystackRef(reference)                      // TODO: rename field to gatewayRef in a follow-up migration
                .status(Order.OrderStatus.PENDING)
                .guest(true)
                .orderedByRole(Order.OrderedByRole.USER)
                .storefrontOrder(false)
                .build();
        orderRepository.save(order);

        log.info("[ORDER] Guest order initiated: orderId={} ref={} email={} phone={} network={} gb={} " +
                        "basePrice={} chargeAmount={}",
                order.getId(), reference, guestEmail, request.getPhoneNumber(),
                request.getNetwork(), request.getCapacityGb(), basePriceGhc, chargeAmountGhc);

        return InitiateOrderResponse.builder()
                .paystackReference(reference)
                .authorizationUrl((String) paystackData.get("checkout_url"))
                .amountGhc(chargeAmountGhc)
                .amountPesewas(chargeAmountGhc.multiply(BigDecimal.valueOf(100)).longValueExact())
                .email(guestEmail)
                .phoneNumber(request.getPhoneNumber())
                .network(request.getNetwork().name())
                .capacityGb(request.getCapacityGb())
                .build();
    }

    /**
     * Initiates a Paystack order for a signed-in customer. Unlike guest checkout,
     * this stores the owning User on the order so it appears in order history.
     */
    @Transactional
    public InitiateOrderResponse initiateUserPaystackOrder(UUID userId, InitiateGuestOrderRequest request) {
        User user = findUserOrThrow(userId);
        PlatformSettings settings = getActiveSettings(request.getNetwork(), request.getCapacityGb());
        BigDecimal basePriceGhc = settings.getPublicPriceGhc();
        BigDecimal chargeAmountGhc = addProcessingCharge(basePriceGhc);
        String reference = REFERENCE_PREFIX + "-" + paystackService.generateReference();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "USER_ORDER");
        metadata.put("userId", userId.toString());
        metadata.put("phone", request.getPhoneNumber());
        metadata.put("network", request.getNetwork().name());
        metadata.put("capacityGb", request.getCapacityGb().toString());
        metadata.put("baseAmountGhc", basePriceGhc.toPlainString());
        metadata.put("customerName", user.getFullName());

        Map<String, Object> paystackData = paystackService.initiateTransaction(
                user.getEmail(), user.getFullName(), chargeAmountGhc, reference,
                buildOrdersRedirectUrl(), metadata);

        Order order = orderRepository.save(Order.builder()
                .user(user)
                .phoneNumber(request.getPhoneNumber())
                .network(request.getNetwork())
                .capacityGb(request.getCapacityGb())
                .costPriceGhc(basePriceGhc)
                .sellingPriceGhc(basePriceGhc)
                .paymentMethod(Order.PaymentMethod.PAYSTACK)
                .paystackRef(reference)
                .status(Order.OrderStatus.PENDING)
                .guest(false)
                .orderedByRole(Order.OrderedByRole.USER)
                .storefrontOrder(false)
                .build());

        log.info("[ORDER] Authenticated Paystack order initiated: orderId={} userId={} ref={} email={} phone={}",
                order.getId(), userId, reference, user.getEmail(), request.getPhoneNumber());
        return InitiateOrderResponse.builder()
                .paystackReference(reference)
                .authorizationUrl((String) paystackData.get("checkout_url"))
                .amountGhc(chargeAmountGhc)
                .amountPesewas(chargeAmountGhc.multiply(BigDecimal.valueOf(100)).longValueExact())
                .email(user.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .network(request.getNetwork().name())
                .capacityGb(request.getCapacityGb())
                .build();
    }

    // ── Guest checkout: step 2 — webhook fulfilment ───────────────────────────

    public void fulfilKorapayOrder(String reference) {
        log.info("[ORDER] fulfilKorapayOrder: ref={}", reference);

        if (processedRefRepository.existsByReference(reference)) {
            log.warn("[ORDER] Duplicate Korapay reference ignored: ref={}", reference);
            return;
        }

        Order order = markKorapayOrderVerified(reference);

        try {
            provisionOrder(order);
            affiliateCommissionService.processCommission(order);
        } catch (UpstreamApiException ex) {
            log.error("[ORDER] Bundle provision failed after Korapay payment: orderId={} ref={} error={}",
                    order.getId(), reference, ex.getMessage());
            markOrderFailedAfterPaymentFailure(order, ex);
            refundPaidMtnRecipientRejection(order, ex);
        }
    }

    /**
     * Safety net for successful Paystack payments whose webhook was delayed,
     * dropped, or configured against an old URL. Only PENDING orders with a
     * Paystack reference are checked, and Paystack's live verification remains
     * the sole authority before any fulfilment is attempted.
     */
    @Scheduled(fixedDelay = 30_000L)
    public void reconcilePendingPaystackOrders() {
        List<Order> pending = orderRepository.findByStatusAndPaystackRefIsNotNull(
                Order.OrderStatus.PENDING);
        for (Order order : pending) {
            String reference = order.getPaystackRef();
            // Legacy refs from the old "databaygh" integration live under a
            // different Paystack secret key — Paystack 400s them on every
            // verify, so skip them instead of burning API calls (and risking
            // rate limits for legitimate verifications) on every cycle.
            if (reference != null && reference.startsWith("databaygh-shop-")) {
                continue;
            }
            try {
                paystackService.verifyTransaction(reference);
                log.info("[PAYSTACK-RECONCILE] Verified missed payment: orderId={} ref={}",
                        order.getId(), reference);
                fulfilKorapayOrder(reference);
            } catch (Exception ex) {
                log.debug("[PAYSTACK-RECONCILE] Not ready: orderId={} ref={} reason={}",
                        order.getId(), reference, ex.getMessage());
            }
        }
    }

    /**
     * Automatic safety net for wallet top-ups: credits any PENDING top-up
     * whose Paystack transaction is confirmed successful — even if the
     * webhook never arrived (provider outage, missed retry window) and the
     * user closed the tab. This also heals top-ups stuck PENDING by the
     * pre-fix webhook 500s, with no manual admin action needed.
     *
     * Runs every 60 seconds. Checkouts Paystack confirms as abandoned/failed
     * and older than 24h are retired to FAILED so we stop polling for
     * transactions that can never complete (Paystack checkouts expire).
     */
    @Scheduled(fixedDelay = 60_000L)
    @Transactional
    public void reconcilePendingWalletTopUps() {
        List<WalletTopUp> pending = walletTopUpRepository.findByStatus(WalletTopUp.Status.PENDING);
        for (WalletTopUp topUp : pending) {
            String reference = topUp.getGatewayRef();
            // Legacy refs from the old "databaygh" integration live under a
            // different Paystack secret key — Paystack 400s them on every
            // verify, so skip them instead of burning API calls on every cycle.
            if (reference != null && reference.startsWith("databaygh-shop-")) {
                continue;
            }
            try {
                processTopUpWebhook(reference);
                log.info("[PAYSTACK-RECONCILE] Verified missed top-up payment: userId={} ref={}",
                        topUp.getUserId(), reference);
            } catch (UpstreamApiException ex) {
                String message = ex.getMessage() == null ? "" : ex.getMessage();
                boolean terminal = message.contains("Status: abandoned")
                        || message.contains("Status: failed");
                if (terminal && topUp.getCreatedAt().isBefore(LocalDateTime.now().minusHours(24))) {
                    topUp.setStatus(WalletTopUp.Status.FAILED);
                    walletTopUpRepository.save(topUp);
                    log.info("[PAYSTACK-RECONCILE] Retired unpaid top-up: userId={} ref={} reason={}",
                            topUp.getUserId(), reference, message);
                } else {
                    log.debug("[PAYSTACK-RECONCILE] Top-up not ready: userId={} ref={} reason={}",
                            topUp.getUserId(), reference, message);
                }
            } catch (Exception ex) {
                log.debug("[PAYSTACK-RECONCILE] Top-up reconcile error: userId={} ref={} reason={}",
                        topUp.getUserId(), reference, ex.getMessage());
            }
        }
    }

    /**
     * BigDreams is the authority for MTN eligibility. If it rejects a paid
     * Korapay order for beneficiary approval, request a full gateway refund.
     * The merchant refund reference is deterministic so duplicate webhooks
     * cannot create a second refund.
     */
    public void refundPaidMtnRecipientRejection(Order order, UpstreamApiException providerFailure) {
        if (!order.isGuest()
                || order.getPaymentMethod() == Order.PaymentMethod.WALLET
                || order.getNetwork() != PlatformSettings.Network.MTN
                || !isMtnRecipientApprovalFailure(providerFailure)
                || order.getPaystackRef() == null) {
            return;
        }

        String refundReference = "DP-RF-" + order.getId();
        order.setKorapayRefundReference(refundReference);
        order.setKorapayRefundStatus("requested");
        order.setKorapayRefundFailure(null);
        orderRepository.save(order);

        try {
            PaystackService.RefundInitiation refund = paystackService.initiateFullRefund(
                    order.getPaystackRef(), refundReference, "DataPack could not verify the MTN recipient");
            order.setKorapayRefundStatus(refund.status());
            if ("failed".equalsIgnoreCase(refund.status())) {
                order.setKorapayRefundFailure("Korapay reported that the refund failed.");
                alertAdminsOfRefundFailure(order, refundReference, order.getKorapayRefundFailure());
            }
            orderRepository.save(order);
            log.info("[KORAPAY] Full refund requested after provider rejection: orderId={} refundReference={} status={}",
                    order.getId(), refundReference, refund.status());
        } catch (RuntimeException refundError) {
            String failure = refundError.getMessage() == null
                    ? "Korapay refund request failed" : refundError.getMessage();
            order.setKorapayRefundStatus("refund_request_failed");
            order.setKorapayRefundFailure(failure.substring(0, Math.min(500, failure.length())));
            orderRepository.save(order);
            alertAdminsOfRefundFailure(order, refundReference, order.getKorapayRefundFailure());
            log.error("[KORAPAY] Refund request failed and needs admin attention: orderId={} refundReference={} error={}",
                    order.getId(), refundReference, failure, refundError);
        }
    }

    private void alertAdminsOfRefundFailure(Order order, String refundReference, String failure) {
        userRepository.findAllByRole(User.Role.SUPER_ADMIN).forEach(admin ->
                notificationService.sendKorapayRefundFailureAlert(
                        admin.getEmail(), admin.getFullName(), order.getId(), refundReference, failure));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected Order markKorapayOrderVerified(String reference) {
        Order order = orderRepository.findByPaystackRef(reference) // TODO: rename repo method to findByGatewayRef
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found for Korapay ref: " + reference));

        order.setStatus(Order.OrderStatus.VERIFIED);
        orderRepository.save(order);

        processedRefRepository.save(ProcessedRef.builder()
                .reference(reference)
                .eventType("GUEST_ORDER")
                .build());

        log.info("[ORDER] Korapay order VERIFIED: orderId={} ref={}", order.getId(), reference);
        return order;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markOrderFailedAfterPaymentFailure(Order order, UpstreamApiException ex) {
        order.setStatus(Order.OrderStatus.FAILED);
        order.setFailureReason(ex.getMessage());
        orderRepository.save(order);

        if (order.getNetwork() == PlatformSettings.Network.MTN && isMtnRecipientApprovalFailure(ex)) {
            unverifiedRecipientService.recordFailure(order.getPhoneNumber(), order.getNetwork(), ex.getMessage(),
                    order.getUser() == null ? null : order.getUser().getEmail(),
                    order.getUser() == null ? null : order.getUser().getFullName());
        }

        log.warn("[ORDER] Order marked FAILED after payment: orderId={}", order.getId());

        if (order.getUser() != null) {
            notificationService.sendOrderFailedAlert(
                    order.getUser().getEmail(), order.getUser().getFullName(),
                    order.getId(), ex.getMessage());
        }
    }

    // ── Wallet top-up: initiate ───────────────────────────────────────────────

    @Transactional
    public TopUpInitiateResponse initiateTopUp(UUID userId, TopUpInitiateRequest request) {
        log.info("[ORDER] initiateTopUp: userId={} amount={}", userId, request.getAmount());

        User   user      = findUserOrThrow(userId);
        String reference = REFERENCE_PREFIX + "-" + paystackService.generateReference();

        BigDecimal baseAmountGhc = request.getAmount();
        BigDecimal chargeAmountGhc = addProcessingCharge(baseAmountGhc);

        // ✅ Persisted BEFORE calling Korapay at all — see WalletTopUp's
        // Javadoc for why. This is what lets the webhook (and the manual
        // verify fallback) find the userId purely from the reference,
        // without depending on Korapay echoing back the metadata we send
        // here (confirmed via live logs that it does not).
        walletTopUpRepository.save(WalletTopUp.builder()
                .gatewayRef(reference)
                .userId(userId)
                .baseAmountGhc(baseAmountGhc)
                .chargeAmountGhc(chargeAmountGhc)
                .status(WalletTopUp.Status.PENDING)
                .build());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type",          "WALLET_TOPUP");
        metadata.put("userId",        userId.toString());
        metadata.put("baseAmountGhc", baseAmountGhc.toPlainString());
        metadata.put("customerName", user.getFullName() + " - " + SITE_PREFIX);

        Map<String, Object> paystackData = paystackService.initiateTransaction(
                user.getEmail(),
                user.getFullName(),
                chargeAmountGhc,
                reference,
                buildRedirectUrl(),
                metadata
        );

        log.info("[ORDER] Wallet top-up initiated: userId={} baseAmount={} chargeAmount={} ref={}",
                userId, baseAmountGhc, chargeAmountGhc, reference);

        return TopUpInitiateResponse.builder()
                .paystackReference(reference)
                .amountGhc(chargeAmountGhc)
                .amountPesewas(chargeAmountGhc.multiply(BigDecimal.valueOf(100)).longValueExact())
                .email(user.getEmail())
                .authorizationUrl((String) paystackData.get("checkout_url"))
                .build();
    }

    // ── Wallet top-up: webhook credit ─────────────────────────────────────────

    /**
     * ✅ FIXED: was processTopUpWebhook(UUID userId, BigDecimal amountGhc,
     * String reference) — those first two params came from webhook
     * metadata, which Korapay's actual charge.success payload never
     * includes (confirmed via live logs). Now takes only the reference,
     * looks up the WalletTopUp row saved at initiate time for the userId,
     * and re-verifies live against Korapay for the amount — matching
     * exactly how the manual "Verify Now" fallback already worked, so both
     * paths are now equally reliable and share the same idempotency guard.
     */
    @Transactional
    public void processTopUpWebhook(String reference) {
        log.info("[ORDER] processTopUpWebhook: ref={}", reference);

        if (processedRefRepository.existsByReference(reference)) {
            log.warn("[ORDER] Duplicate top-up reference ignored: ref={}", reference);
            return;
        }

        WalletTopUp topUp = walletTopUpRepository.findByGatewayRef(reference)
                .orElseThrow(() -> new UpstreamApiException(
                        "No WalletTopUp record found for ref=" + reference
                                + " — cannot credit without a known userId"));

        if (topUp.getStatus() != WalletTopUp.Status.PENDING) {
            log.info("[ORDER] Top-up is already resolved; no credit applied: ref={} status={}",
                    reference, topUp.getStatus());
            return;
        }

        Map<String, Object> txData          = paystackService.verifyTransaction(reference);
        BigDecimal          chargedAmountGhc = paystackService.extractAmountGhc(txData);
        assertTopUpChargeMatches(topUp, chargedAmountGhc, reference);
        BigDecimal          baseAmountGhc    = removeProcessingCharge(chargedAmountGhc);

        walletService.credit(topUp.getUserId(), baseAmountGhc, TransactionType.TOPUP,
                "Wallet top-up via Korapay", reference);

        topUp.setStatus(WalletTopUp.Status.COMPLETED);
        topUp.setCompletedAt(LocalDateTime.now());
        walletTopUpRepository.save(topUp);

        processedRefRepository.save(ProcessedRef.builder()
                .reference(reference)
                .eventType("WALLET_TOPUP")
                .build());

        log.info("[ORDER] ✔ Wallet top-up credited via webhook: userId={} amount={} ref={}",
                topUp.getUserId(), baseAmountGhc, reference);
    }

    // ── Wallet top-up: manual verify fallback ─────────────────────────────────

    @Transactional
    public WalletResponse verifyTopUp(UUID userId, TopUpVerifyRequest request) {
        log.info("[ORDER] verifyTopUp: userId={} ref={}", userId, request.getPaystackRef());

        WalletTopUp topUp = walletTopUpRepository.findByGatewayRef(request.getPaystackRef())
                .orElseThrow(() -> new ValidationException(
                        "This payment reference is not a valid pending wallet top-up."));
        if (!userId.equals(topUp.getUserId())) {
            throw new ValidationException("This payment reference does not belong to the current account.");
        }
        if (topUp.getStatus() != WalletTopUp.Status.PENDING
                || processedRefRepository.existsByReference(request.getPaystackRef())) {
            log.info("[ORDER] Top-up already processed: ref={} status={}",
                    request.getPaystackRef(), topUp.getStatus());
            return WalletResponse.builder()
                    .userId(userId)
                    .balance(walletService.getBalance(userId))
                    .build();
        }

        Map<String, Object> txData         = paystackService.verifyTransaction(
                request.getPaystackRef());
        BigDecimal          chargedAmountGhc = paystackService.extractAmountGhc(txData);
        assertTopUpChargeMatches(topUp, chargedAmountGhc, request.getPaystackRef());
        BigDecimal          baseAmountGhc    = removeProcessingCharge(chargedAmountGhc);

        walletService.credit(topUp.getUserId(), baseAmountGhc, TransactionType.TOPUP,
                "Wallet top-up (manual verify)", request.getPaystackRef());

        processedRefRepository.save(ProcessedRef.builder()
                .reference(request.getPaystackRef())
                .eventType("WALLET_TOPUP")
                .build());

        // Keep the WalletTopUp record's status consistent regardless of
        // which path (webhook or manual verify) actually completes it
        // first — purely for accurate admin/reporting history, since the
        // idempotency guard above already prevents any double-credit.
        topUp.setStatus(WalletTopUp.Status.COMPLETED);
        topUp.setCompletedAt(LocalDateTime.now());
        walletTopUpRepository.save(topUp);

        log.info("[ORDER] Manual top-up verify success: userId={} chargedAmount={} creditedAmount={} ref={}",
                userId, chargedAmountGhc, baseAmountGhc, request.getPaystackRef());

        return WalletResponse.builder()
                .userId(userId)
                .balance(walletService.getBalance(userId))
                .build();
    }

    // ── User wallet order ─────────────────────────────────────────────────────

    public OrderResponse placeWalletOrder(UUID userId, WalletOrderRequest request) {
        log.info("[ORDER] placeWalletOrder: userId={} phone={} network={} gb={}",
                userId, request.getPhoneNumber(), request.getNetwork(), request.getCapacityGb());

        User user = findUserOrThrow(userId);
        PlatformSettings settings = getActiveSettings(request.getNetwork(), request.getCapacityGb());

        BigDecimal price = pricingService.resolvePriceForUser(user, settings);

        rejectIfDuplicate(userId, request.getPhoneNumber(), request.getNetwork(),
                request.getCapacityGb(), "USER");

        walletService.debit(userId, price, TransactionType.PURCHASE,
                "Data bundle " + request.getCapacityGb() + "GB " + request.getNetwork(), null);

        Order order;
        try {
            order = saveNewOrder(createPendingOrder(user, request, price));
        } catch (DataIntegrityViolationException ex) {
            log.warn("[ORDER] DB idempotency constraint blocked duplicate wallet order: " +
                            "userId={} phone={} network={} gb={}",
                    userId, request.getPhoneNumber(), request.getNetwork(), request.getCapacityGb());
            walletService.credit(userId, price, TransactionType.REFUND,
                    "Refund: duplicate order rejected (phone=" + request.getPhoneNumber()
                            + ", network=" + request.getNetwork()
                            + ", gb=" + request.getCapacityGb() + ")",
                    null);
            throw new DuplicateOrderException(
                    "A similar order was already placed in the last "
                            + DUPLICATE_WINDOW_SECONDS + " seconds.");
        }

        try {
            provisionOrder(order);
            affiliateCommissionService.processCommission(order);
        } catch (UpstreamApiException ex) {
            handleProvisioningFailure(order.getId(), user, price, ex);
            if (isMtnRecipientApprovalFailure(ex)) {
                throw new ValidationException(mtnRecipientFailureMessage(ex, true));
            }
        }

        return toOrderResponse(orderRepository.findById(order.getId()).orElseThrow());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected Order saveNewOrder(Order order) {
        return orderRepository.save(order);
    }

    private Order createPendingOrder(User user, WalletOrderRequest request, BigDecimal price) {
        return Order.builder()
                .user(user)
                .phoneNumber(request.getPhoneNumber())
                .network(request.getNetwork())
                .capacityGb(request.getCapacityGb())
                .costPriceGhc(price)
                .sellingPriceGhc(price)
                .paymentMethod(Order.PaymentMethod.WALLET)
                .status(Order.OrderStatus.PENDING)
                .guest(false)
                .orderedByRole(Order.OrderedByRole.USER)
                .storefrontOrder(false)
                .idempotencyKey(buildIdempotencyKey(user.getId(), request.getPhoneNumber(),
                        request.getNetwork(), request.getCapacityGb()))
                .build();
    }

    private boolean isMtnRecipientApprovalFailure(UpstreamApiException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        return message.contains("beneficiary_required")
                || message.contains("beneficiary required")
                || message.contains("not verified")
                || message.contains("unverified")
                || (message.contains("not approved") && message.contains("sent for approval"));
    }

    private String mtnRecipientFailureMessage(UpstreamApiException ex, boolean walletRefunded) {
        String refundMessage = walletRefunded ? "Your wallet has been refunded."
                : "A Korapay refund request has been started.";
        String providerDetails = ex.getMessage() == null ? ""
                : " Diagnostic details: " + DataPackBranding.forDisplay(ex.getMessage());
        return "DataPack could not verify this MTN recipient (BENEFICIARY_REQUIRED). The order was not delivered. "
                + refundMessage + providerDetails;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void handleProvisioningFailure(Long orderId, User user, BigDecimal price, UpstreamApiException ex) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(Order.OrderStatus.FAILED);
        order.setFailureReason(ex.getMessage());
        orderRepository.save(order);
        if (order.getNetwork() == PlatformSettings.Network.MTN && isMtnRecipientApprovalFailure(ex)) {
            unverifiedRecipientService.recordFailure(order.getPhoneNumber(), order.getNetwork(), ex.getMessage(),
                    user.getEmail(), user.getFullName());
        }
        walletService.credit(user.getId(), price, TransactionType.REFUND,
                "Refund: failed bundle delivery for order #" + order.getId(), null);
        notificationService.sendOrderFailedAlert(user.getEmail(), user.getFullName(), order.getId(), ex.getMessage());

        log.error("[ORDER] Provisioning failed, wallet refunded: orderId={} userId={} amount={} error={}",
                orderId, user.getId(), price, ex.getMessage());
    }

    // ── Reseller wallet order ─────────────────────────────────────────────────

    public OrderResponse placeResellerWalletOrder(UUID userId, WalletOrderRequest request,
                                                  BigDecimal sellingPriceGhc) {
        log.info("[ORDER] placeResellerWalletOrder: userId={} phone={} network={} gb={}",
                userId, request.getPhoneNumber(), request.getNetwork(), request.getCapacityGb());

        User             user      = findUserOrThrow(userId);
        PlatformSettings settings  = getActiveSettings(
                request.getNetwork(), request.getCapacityGb());
        BigDecimal       costPrice = settings.getResellerPriceGhc();

        rejectIfDuplicate(userId, request.getPhoneNumber(), request.getNetwork(),
                request.getCapacityGb(), "RESELLER");

        walletService.debit(userId, costPrice, TransactionType.PURCHASE,
                "Reseller bundle " + request.getCapacityGb() + "GB " + request.getNetwork(),
                null);

        Order order = Order.builder()
                .user(user)
                .phoneNumber(request.getPhoneNumber())
                .network(request.getNetwork())
                .capacityGb(request.getCapacityGb())
                .costPriceGhc(costPrice)
                .sellingPriceGhc(sellingPriceGhc != null ? sellingPriceGhc : costPrice)
                .paymentMethod(Order.PaymentMethod.WALLET)
                .status(Order.OrderStatus.PENDING)
                .guest(false)
                .orderedByRole(Order.OrderedByRole.RESELLER)
                .storefrontOrder(false)
                .idempotencyKey(buildIdempotencyKey(userId, request.getPhoneNumber(),
                        request.getNetwork(), request.getCapacityGb()))
                .build();

        try {
            order = saveNewOrder(order);
        } catch (DataIntegrityViolationException ex) {
            log.warn("[ORDER] DB idempotency constraint blocked duplicate reseller order: " +
                            "userId={} phone={} network={} gb={}",
                    userId, request.getPhoneNumber(), request.getNetwork(),
                    request.getCapacityGb());

            walletService.credit(userId, costPrice, TransactionType.REFUND,
                    "Refund: duplicate order rejected (phone=" + request.getPhoneNumber()
                            + ", network=" + request.getNetwork()
                            + ", gb=" + request.getCapacityGb() + ")",
                    null);

            log.info("[ORDER] Wallet refunded after duplicate rejection: userId={} amount={}",
                    userId, costPrice);

            throw new DuplicateOrderException(
                    "A similar order was already placed in the last "
                            + DUPLICATE_WINDOW_SECONDS + " seconds.");
        }

        log.info("[ORDER] Reseller wallet order placed: userId={} orderId={} phone={} " +
                        "network={} gb={} costPrice={} sellingPrice={}",
                userId, order.getId(), request.getPhoneNumber(),
                request.getNetwork(), request.getCapacityGb(), costPrice, sellingPriceGhc);

        try {
            provisionOrder(order);
            affiliateCommissionService.processCommission(order);
        } catch (UpstreamApiException ex) {
            log.error("[ORDER] Big Dreams provision failed for reseller order: " +
                            "orderId={} error={}",
                    order.getId(), ex.getMessage());
            markResellerOrderFailed(order.getId(), user, costPrice, ex);
            if (isMtnRecipientApprovalFailure(ex)) {
                throw new ValidationException(mtnRecipientFailureMessage(ex, true));
            }
        }

        return toOrderResponse(orderRepository.findById(order.getId()).orElseThrow());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markResellerOrderFailed(Long orderId, User user, BigDecimal costPrice,
                                           UpstreamApiException ex) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(Order.OrderStatus.FAILED);
        order.setFailureReason(ex.getMessage());
        orderRepository.save(order);
        if (order.getNetwork() == PlatformSettings.Network.MTN && isMtnRecipientApprovalFailure(ex)) {
            unverifiedRecipientService.recordFailure(order.getPhoneNumber(), order.getNetwork(), ex.getMessage(),
                    user.getEmail(), user.getFullName());
        }

        walletService.credit(user.getId(), costPrice, TransactionType.REFUND,
                "Refund: failed bundle delivery for order #" + order.getId(), null);

        log.info("[ORDER] Wallet refunded: userId={} orderId={} amount={}",
                user.getId(), order.getId(), costPrice);

        notificationService.sendOrderFailedAlert(
                user.getEmail(), user.getFullName(), order.getId(), ex.getMessage());
    }

    // ── Big Dreams provisioning helper ────────────────────────────────────────
    /**
     * Places the paid order through the documented Big Dreams Share Bundles
     * actions. PlatformSettings remains the source of truth for availability
     * and customer/reseller prices; the provider share balance is used only for
     * delivery. The internal order ID is sent as the provider's optional
     * order_id so retries cannot charge the share balance twice.
     */
    private void provisionOrder(Order order) {
        String providerOrderId = "datapack-" + order.getId();
        BigDreamsDataService.ShareResult result;
        switch (order.getNetwork()) {
            case MTN -> {
                if (order.getCapacityGb().stripTrailingZeros().scale() > 0) {
                    throw new UpstreamApiException("MTN bundles require a whole-number GB amount.");
                }
                BigDreamsDataService.PlaceOrderResult placeOrder = bigDreamsDataService.placeOrder(
                        "mtn", order.getPhoneNumber(), order.getCapacityGb().intValueExact(), providerOrderId);
                order.setDbhPurchaseId(placeOrder.transactionId());
                order.setDbhReference(placeOrder.reference() != null ? placeOrder.reference() : placeOrder.orderId());
                order.setStatus(toOrderStatus(placeOrder.status()));
                orderRepository.save(order);
                log.info("[ORDER] Big Dreams regular MTN order accepted: orderId={} providerOrderId={} status={}",
                        order.getId(), providerOrderId, placeOrder.status());
                return;
            }
            case TELECEL -> result = bigDreamsDataService.shareTelecel(
                    order.getPhoneNumber(), order.getCapacityGb(), providerOrderId);
            case AIRTELTIGO -> {
                BigDecimal mb = order.getCapacityGb().multiply(BigDecimal.valueOf(1000));
                if (mb.stripTrailingZeros().scale() > 0) {
                    throw new UpstreamApiException("iShare bundles must resolve to a whole MB amount.");
                }
                result = bigDreamsDataService.shareIShare(order.getPhoneNumber(),
                        mb.intValueExact(), providerOrderId);
            }
            default -> throw new UpstreamApiException("Unsupported share-bundle network: " + order.getNetwork());
        }
        order.setDbhPurchaseId(null);
        order.setDbhReference(result.orderId());
        String providerStatus = result.status() == null ? "" : result.status().trim().toLowerCase()
                .replace('-', '_').replace(' ', '_');
        order.setStatus(providerStatus.equals("completed") || providerStatus.equals("complete")
                || providerStatus.equals("delivered") || providerStatus.equals("success")
                || providerStatus.equals("successful") || providerStatus.equals("successful_delivery")
                || providerStatus.equals("delivery_successful") || providerStatus.equals("done")
                ? Order.OrderStatus.COMPLETED : Order.OrderStatus.PENDING);
        orderRepository.save(order);
        log.info("[ORDER] Big Dreams share accepted orderId={} providerOrderId={} reference={} status={}",
                order.getId(), providerOrderId, result.orderId(), result.status());
    }

    private Order.OrderStatus toOrderStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase()
                .replace('-', '_').replace(' ', '_');
        return normalized.equals("completed") || normalized.equals("complete")
                || normalized.equals("delivered") || normalized.equals("success")
                || normalized.equals("successful") || normalized.equals("done")
                ? Order.OrderStatus.COMPLETED : Order.OrderStatus.PENDING;
    }

    // ── Order queries ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrders(UUID userId, Pageable pageable) {
        User user = findUserOrThrow(userId);
        return orderRepository.findByUserOrderByCreatedAtDesc(user, pageable)
                .map(this::toOrderResponse);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found: " + orderId));
        if (order.getUser() == null || !order.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You do not own this order.");
        }
        return toOrderResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderStatusByRef(String reference) {
        Order order = orderRepository.findByPaystackRef(reference) // TODO: rename repo method to findByGatewayRef
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found for ref: " + reference));
        return toOrderResponse(order);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
        // hard-coded canonical site URL fallback.
        // Paystack must return customers to the main dashboard, not the old
        // checker/payment callback screen.
        return frontendUrlResolver.resolveBaseUrl() + "/dashboard";
    }

    private String buildOrdersRedirectUrl() {
        return frontendUrlResolver.resolveBaseUrl() + "/orders";
    }

    private void rejectIfDuplicate(UUID userId, String phoneNumber,
                                   PlatformSettings.Network network,
                                   BigDecimal capacityGb, String role) {
        LocalDateTime windowStart = LocalDateTime.now().minusSeconds(DUPLICATE_WINDOW_SECONDS);

        boolean duplicate = orderRepository
                .existsByUserIdAndPhoneNumberAndNetworkAndCapacityGbAndStatusNotAndCreatedAtAfter(
                        userId, phoneNumber, network, capacityGb,
                        Order.OrderStatus.FAILED, windowStart);

        if (duplicate) {
            log.warn("[ORDER] Duplicate rejected: role={} userId={} phone={} network={} gb={} " +
                            "window={}s",
                    role, userId, phoneNumber, network, capacityGb, DUPLICATE_WINDOW_SECONDS);
            throw new DuplicateOrderException(
                    "A similar order was already placed in the last "
                            + DUPLICATE_WINDOW_SECONDS + " seconds. "
                            + "Please wait before trying again.");
        }
    }

    private String buildIdempotencyKey(UUID userId, String phoneNumber,
                                       PlatformSettings.Network network,
                                       BigDecimal capacityGb) {
        long bucket = System.currentTimeMillis() / 1000L / DUPLICATE_WINDOW_SECONDS;
        return userId + ":" + phoneNumber + ":" + network.name()
                + ":" + capacityGb.toPlainString() + ":" + bucket;
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private PlatformSettings getActiveSettings(PlatformSettings.Network network,
                                               BigDecimal capacityGb) {
        return platformSettingsRepository
                .findByNetworkAndCapacityGbAndActiveTrue(network, capacityGb)
                .orElseThrow(() -> new BundleNotFoundException(
                        "Bundle not available: network=" + network
                                + " capacityGb=" + capacityGb));
    }

    // ── Processing charge helpers ────────────────────────────────────────────

    private BigDecimal addProcessingCharge(BigDecimal baseAmountGhc) {
        return baseAmountGhc
                .multiply(BigDecimal.ONE.add(PROCESSING_CHARGE_RATE))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal removeProcessingCharge(BigDecimal chargedAmountGhc) {
        return chargedAmountGhc
                .divide(BigDecimal.ONE.add(PROCESSING_CHARGE_RATE), 2, RoundingMode.HALF_UP);
    }

    private void assertTopUpChargeMatches(WalletTopUp topUp, BigDecimal chargedAmountGhc,
                                          String reference) {
        if (chargedAmountGhc == null || topUp.getChargeAmountGhc() == null
                || topUp.getChargeAmountGhc().compareTo(chargedAmountGhc) != 0) {
            log.error("[ORDER] Top-up amount mismatch; refusing wallet credit: ref={} expected={} actual={}",
                    reference, topUp.getChargeAmountGhc(), chargedAmountGhc);
            throw new ValidationException("The confirmed payment amount does not match this wallet top-up.");
        }
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    private OrderResponse toOrderResponse(Order o) {
        return OrderResponse.builder()
                .id(o.getId())
                .phoneNumber(o.getPhoneNumber())
                .network(o.getNetwork().name())
                .capacityGb(o.getCapacityGb())
                .costPriceGhc(o.getCostPriceGhc())
                .sellingPriceGhc(o.getSellingPriceGhc())
                .paymentMethod(o.getPaymentMethod().name())
                .paystackRef(o.getPaystackRef())
                .korapayRefundReference(o.getKorapayRefundReference())
                .korapayRefundStatus(o.getKorapayRefundStatus())
                .status(o.getStatus().name())
                .failureReason(DataPackBranding.forDisplay(o.getFailureReason()))
                .guest(o.isGuest())
                .storefrontOrder(o.isStorefrontOrder())
                .userFullName(o.getUser() == null ? null : o.getUser().getFullName())
                .userEmail(o.getUser() == null ? null : o.getUser().getEmail())
                .userPhone(o.getUser() == null ? null : o.getUser().getPhone())
                .createdAt(o.getCreatedAt())
                .updatedAt(o.getUpdatedAt())
                .build();
    }
}
