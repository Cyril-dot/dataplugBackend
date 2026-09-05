package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.config.AppConfig;
import com.databundleHum.OnetBundleHub.util.FrontendUrlResolver;
import com.databundleHum.OnetBundleHub.dtos.InitiateGuestOrderRequest;
import com.databundleHum.OnetBundleHub.dtos.TopUpInitiateRequest;
import com.databundleHum.OnetBundleHub.dtos.*;
import com.databundleHum.OnetBundleHub.dtos.response.InitiateOrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.OrderResponse;
import com.databundleHum.OnetBundleHub.dtos.response.TopUpInitiateResponse;
import com.databundleHum.OnetBundleHub.dtos.response.WalletResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles all order flows:
 *  - Guest checkout  (Korapay Checkout Redirect → webhook → DataPrimo provision)
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
 * Runs entirely on KorapayService now. Key differences from Paystack:
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
    private static final String SITE_PREFIX = "databaygh.shop";

    // ✅ FIXED — same bug confirmed live on the Korapay checker path
    // (Railway logs: 422 "reference must only contain alphanumeric, hyphen
    // and underscore characters"). Paystack references may or may not
    // enforce the same restriction, but there's no reason to risk it —
    // this sanitized prefix (no dot) is now used for every reference built
    // here, while SITE_PREFIX (with its dot) stays as-is for the payer
    // email domain and free-text metadata, where a dot is fine/required.
    private static final String REFERENCE_PREFIX = "databaygh-shop";

    private final OrderRepository             orderRepository;
    private final UserRepository              userRepository;
    private final PlatformSettingsRepository  platformSettingsRepository;
    private final ProcessedRefRepository      processedRefRepository;
    private final WalletTopUpRepository       walletTopUpRepository;
    private final WalletService               walletService;
    private final KorapayService              korapayService;
    private final DataPrimoService            dataPrimoService;
    private final NotificationService         notificationService;
    private final AffiliateCommissionService  affiliateCommissionService;
    private final AppConfig                   appConfig;
    private final FrontendUrlResolver          frontendUrlResolver;
    private final PricingService pricingService;

    // ── Guest checkout: step 1 — initiate ────────────────────────────────────

    @Transactional
    public InitiateOrderResponse initiateGuestOrder(InitiateGuestOrderRequest request) {
        log.info("[ORDER] initiateGuestOrder: phone={} network={} gb={}",
                request.getPhoneNumber(), request.getNetwork(), request.getCapacityGb());

        PlatformSettings settings = getActiveSettings(
                request.getNetwork(), request.getCapacityGb());

        BigDecimal basePriceGhc = settings.getPublicPriceGhc();
        BigDecimal chargeAmountGhc = addProcessingCharge(basePriceGhc);

        String reference  = REFERENCE_PREFIX + "-" + korapayService.generateReference();
        String guestEmail = buildPayerEmail(request.getPhoneNumber());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type",         "GUEST_ORDER");
        metadata.put("phone",        request.getPhoneNumber());
        metadata.put("network",      request.getNetwork().name());
        metadata.put("capacityGb",   request.getCapacityGb().toString());
        metadata.put("baseAmountGhc", basePriceGhc.toPlainString());
        metadata.put("customerName", request.getPhoneNumber() + " - " + SITE_PREFIX);

        Map<String, Object> korapayData = korapayService.initiateTransaction(
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
                .authorizationUrl((String) korapayData.get("checkout_url"))
                .amountGhc(chargeAmountGhc)
                .email(guestEmail)
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
            markOrderFailedAfterPaymentFailure(order);
        }
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
    protected void markOrderFailedAfterPaymentFailure(Order order) {
        order.setStatus(Order.OrderStatus.FAILED);
        orderRepository.save(order);

        log.warn("[ORDER] Order marked FAILED after payment: orderId={}", order.getId());

        if (order.getUser() != null) {
            notificationService.sendOrderFailedAlert(
                    order.getUser().getEmail(), order.getUser().getFullName(),
                    order.getId());
        }
    }

    // ── Wallet top-up: initiate ───────────────────────────────────────────────

    @Transactional
    public TopUpInitiateResponse initiateTopUp(UUID userId, TopUpInitiateRequest request) {
        log.info("[ORDER] initiateTopUp: userId={} amount={}", userId, request.getAmount());

        User   user      = findUserOrThrow(userId);
        String reference = REFERENCE_PREFIX + "-" + korapayService.generateReference();

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

        Map<String, Object> korapayData = korapayService.initiateTransaction(
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
                .email(user.getEmail())
                .authorizationUrl((String) korapayData.get("checkout_url"))
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

        Map<String, Object> txData          = korapayService.verifyTransaction(reference);
        BigDecimal          chargedAmountGhc = korapayService.extractAmountGhc(txData);
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

        if (processedRefRepository.existsByReference(request.getPaystackRef())) {
            log.info("[ORDER] Top-up already processed: ref={}", request.getPaystackRef());
            return WalletResponse.builder()
                    .userId(userId)
                    .balance(walletService.getBalance(userId))
                    .build();
        }

        Map<String, Object> txData         = korapayService.verifyTransaction(
                request.getPaystackRef());
        BigDecimal          chargedAmountGhc = korapayService.extractAmountGhc(txData);
        BigDecimal          baseAmountGhc    = removeProcessingCharge(chargedAmountGhc);

        walletService.credit(userId, baseAmountGhc, TransactionType.TOPUP,
                "Wallet top-up (manual verify)", request.getPaystackRef());

        processedRefRepository.save(ProcessedRef.builder()
                .reference(request.getPaystackRef())
                .eventType("WALLET_TOPUP")
                .build());

        // Keep the WalletTopUp record's status consistent regardless of
        // which path (webhook or manual verify) actually completes it
        // first — purely for accurate admin/reporting history, since the
        // idempotency guard above already prevents any double-credit.
        walletTopUpRepository.findByGatewayRef(request.getPaystackRef()).ifPresent(topUp -> {
            topUp.setStatus(WalletTopUp.Status.COMPLETED);
            topUp.setCompletedAt(LocalDateTime.now());
            walletTopUpRepository.save(topUp);
        });

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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void handleProvisioningFailure(Long orderId, User user, BigDecimal price, UpstreamApiException ex) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(Order.OrderStatus.FAILED);
        orderRepository.save(order);
        walletService.credit(user.getId(), price, TransactionType.REFUND,
                "Refund: failed bundle delivery for order #" + order.getId(), null);
        notificationService.sendOrderFailedAlert(user.getEmail(), user.getFullName(), order.getId());

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
            log.error("[ORDER] DataPrimo provision failed for reseller order: " +
                            "orderId={} error={}",
                    order.getId(), ex.getMessage());
            markResellerOrderFailed(order.getId(), user, costPrice);
        }

        return toOrderResponse(orderRepository.findById(order.getId()).orElseThrow());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markResellerOrderFailed(Long orderId, User user, BigDecimal costPrice) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(Order.OrderStatus.FAILED);
        orderRepository.save(order);

        walletService.credit(user.getId(), costPrice, TransactionType.REFUND,
                "Refund: failed bundle delivery for order #" + order.getId(), null);

        log.info("[ORDER] Wallet refunded: userId={} orderId={} amount={}",
                user.getId(), order.getId(), costPrice);

        notificationService.sendOrderFailedAlert(
                user.getEmail(), user.getFullName(), order.getId());
    }

    // ── DataPrimo provisioning helper ─────────────────────────────────────────

    /**
     * Resolves this order's (network, capacityGb) to a DataPrimo
     * productId/network via PlatformSettings, then calls DataPrimoService.
     * Fails fast (before any HTTP call) if the bundle hasn't been
     * catalog-mapped yet.
     */
    private void provisionOrder(Order order) {
        PlatformSettings settings = getActiveSettings(order.getNetwork(), order.getCapacityGb());

        String productId = settings.getDataprimoProductId();
        String network    = settings.getDataprimoNetwork();

        log.info("[ORDER] provisionOrder: orderId={} network={} capacityGb={} → dataprimoProductId={} dataprimoNetwork={}",
                order.getId(), order.getNetwork(), order.getCapacityGb(), productId, network);

        if (productId == null || productId.isBlank() || network == null || network.isBlank()) {
            log.error("[ORDER] No DataPrimo catalog mapping for orderId={} network={} capacityGb={}",
                    order.getId(), order.getNetwork(), order.getCapacityGb());
            throw new UpstreamApiException(
                    "Bundle network=" + order.getNetwork() + " capacityGb=" + order.getCapacityGb()
                            + " has no DataPrimo catalog mapping (dataprimoProductId/dataprimoNetwork "
                            + "not set on PlatformSettings) — cannot provision orderId=" + order.getId());
        }

        dataPrimoService.purchase(order, productId, network);
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
        // static app.base-url config.
        return frontendUrlResolver.resolveBaseUrl() + "/payment/callback";
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
                .status(o.getStatus().name())
                .guest(o.isGuest())
                .storefrontOrder(o.isStorefrontOrder())
                .createdAt(o.getCreatedAt())
                .updatedAt(o.getUpdatedAt())
                .build();
    }
}
