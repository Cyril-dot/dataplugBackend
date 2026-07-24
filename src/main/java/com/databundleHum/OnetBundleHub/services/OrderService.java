package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.config.AppConfig;
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
 *  - Guest checkout  (Paystack popup → webhook → Big Dreams Data provision)
 *  - User wallet purchase
 *  - Reseller wallet purchase (wholesale price)
 *  - Order status queries
 *
 * ── TRANSACTION-BOUNDARY FIX (2026-07-09) ────────────────────────────────────
 *
 * Previously, placeWalletOrder / placeResellerWalletOrder / fulfilPaystackOrder
 * were each annotated with a single top-level @Transactional. Even though
 * BigDreamsService.purchase() and AffiliateCommissionService.processCommission()
 * run in their own REQUIRES_NEW transactions, Spring's REQUIRES_NEW SUSPENDS
 * the outer transaction rather than closing it — the outer method's DB
 * connection stays open and idle for the entire duration of:
 *   - the outbound HTTP call(s) to Big Dreams (with retries), and
 *   - the commission check (which was observed taking ~5 minutes)
 *
 * Postgres's idle_in_transaction_session_timeout then kills that idle
 * connection, the outer transaction rolls back, and the order INSERT that
 * happened earlier in that same outer transaction is undone — the order row
 * disappears entirely even though BigDreams already accepted it and it was
 * already visible to other transactions as PENDING.
 *
 * THE FIX: none of the three flows below hold an open transaction across an
 * external call anymore. Each flow is a plain (non-@Transactional) orchestrator
 * method that:
 *   1. calls small, dedicated @Transactional(REQUIRES_NEW) methods for each
 *      DB write (debit, save, mark-failed, mark-verified), each committing
 *      and releasing its connection immediately, then
 *   2. calls out to BigDreamsService / AffiliateCommissionService with NO
 *      transaction open on the calling thread at all.
 *
 * This means a slow commission check or a slow upstream API call can never
 * again cause a committed order row to vanish on rollback.
 *
 * ── PAYSTACK PROCESSING CHARGE (2026-07-10) ──────────────────────────────────
 *
 * A 10% processing charge is now passed on to the paying customer at the
 * exact moment real money moves through Paystack:
 *   - Guest checkout (initiateGuestOrder): the guest pays bundle price × 1.10.
 *   - Wallet top-up (initiateTopUp / webhook / manual verify): the customer
 *     pays top-up amount × 1.10, but only the ORIGINAL top-up amount is ever
 *     credited to their wallet.
 *
 * Wallet-funded order placement (placeWalletOrder / placeResellerWalletOrder)
 * is deliberately left untouched — that money already had the 10% collected
 * from the customer when it entered the wallet via top-up, so charging it
 * again on spend would double-charge them.
 *
 * IMPORTANT: WebhookController must forward the BASE (pre-charge) amount —
 * read from Paystack metadata key "baseAmountGhc" — into
 * processTopUpWebhook(), not the raw amount Paystack reports as paid
 * (which includes the 10% charge). Crediting the raw Paystack amount would
 * silently give the customer a free 10% top-up.
 *
 * ── MISSING authorizationUrl FIX (2026-07-10) ────────────────────────────────
 *
 * initiateGuestOrder() was calling paystackService.initiateTransaction(...)
 * and discarding the return value entirely, so InitiateOrderResponse never
 * carried the Paystack authorization_url back to the frontend. The frontend
 * had nothing to redirect the guest to for payment approval, even though an
 * Order row was already saved as PENDING. The webhook path was never the
 * issue — fulfilPaystackOrder() only runs on a verified charge.success event,
 * so nothing was ever provisioned without a real payment. The fix below
 * mirrors initiateTopUp(): capture the Paystack init response and populate
 * authorizationUrl on InitiateOrderResponse.
 *
 * ── PAYSTACK REFERENCE / METADATA SITE PREFIX (2026-07-23) ───────────────────
 *
 * Every Paystack reference generated by this service (guest orders and wallet
 * top-ups) is now prefixed with the site domain "databaygh.shop-" so that
 * transactions are immediately recognizable in the Paystack dashboard and in
 * any exported settlement reports, e.g.:
 *
 *   databaygh.shop-A1B2C3D4E5F6A1B2
 *
 * In addition, a human-readable "customerName" field is now included in the
 * metadata sent to Paystack:
 *   - Guest orders:  "<phoneNumber> - databaygh.shop"
 *   - Wallet top-ups: "<user full name> - databaygh.shop"
 *
 * This is purely additive — it does not change reference uniqueness
 * guarantees (paystackService.generateReference() output is still appended
 * after the prefix), does not affect amount/charge calculations, and does
 * not alter the transaction-boundary fixes above.
 *
 * ── RECIPIENT-NUMBER PAYER EMAIL (2026-07-24) ────────────────────────────────
 *
 * Paystack receipts render the payer as "<email> just paid <business>". To make
 * receipts self-identifying, the email sent to Paystack for guest orders is now
 * built from the RECIPIENT phone number on the request — the line the data is
 * actually being bought for — plus the site domain:
 *
 *   0592451539@databaygh.shop
 *
 * Wallet top-ups have no recipient number (the customer is funding their own
 * wallet, not buying data for a line), so they continue to use the user's real
 * account email. Wallet-funded order placement never touches Paystack at all —
 * that money was already collected at top-up time — so no email is built there.
 *
 * NOTE: the previous guest email was derived from appConfig.getAppBaseUrl(),
 * which meant a "www." host leaked into the address ("guest@www.databaygh.shop").
 * SITE_PREFIX is the bare domain, so that no longer happens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final int DUPLICATE_WINDOW_SECONDS = 30;

    /** 10% processing charge passed on to the customer at the point of payment. */
    private static final BigDecimal PAYSTACK_CHARGE_RATE = new BigDecimal("0.10");

    /**
     * Bare site domain (no scheme, no "www.") used to prefix Paystack references,
     * build payer email addresses and label customers in metadata.
     */
    private static final String SITE_PREFIX = "databaygh.shop";

    private final OrderRepository             orderRepository;
    private final UserRepository              userRepository;
    private final PlatformSettingsRepository  platformSettingsRepository;
    private final ProcessedRefRepository      processedRefRepository;
    private final WalletService               walletService;
    private final PaystackService             paystackService;
    private final BigDreamsService            bigDreamsService;
    private final NotificationService         notificationService;
    private final AffiliateCommissionService  affiliateCommissionService;
    private final AppConfig                   appConfig;
    private final PricingService pricingService;

    // ── Guest checkout: step 1 — initiate ────────────────────────────────────

    @Transactional
    public InitiateOrderResponse initiateGuestOrder(InitiateGuestOrderRequest request) {
        PlatformSettings settings = getActiveSettings(
                request.getNetwork(), request.getCapacityGb());

        // Base bundle price (tracked on the order for cost/commission purposes).
        BigDecimal basePriceGhc = settings.getPublicPriceGhc();
        // What the guest is actually charged via Paystack, including the 10% fee.
        BigDecimal chargeAmountGhc = addPaystackCharge(basePriceGhc);

        // Reference is prefixed with the site domain so it's immediately
        // recognizable in the Paystack dashboard, e.g. "databaygh.shop-A1B2C3".
        String reference  = SITE_PREFIX + "-" + paystackService.generateReference();
        // Payer email is built from the RECIPIENT number on this request — the
        // line the bundle is being bought for — so the Paystack receipt reads
        // "0592451539@databaygh.shop just paid ...".
        String guestEmail = buildPayerEmail(request.getPhoneNumber());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type",         "GUEST_ORDER");
        metadata.put("phone",        request.getPhoneNumber());
        metadata.put("network",      request.getNetwork().name());
        metadata.put("capacityGb",   request.getCapacityGb().toString());
        metadata.put("baseAmountGhc", basePriceGhc.toPlainString());
        // Human-readable customer label combining recipient number + site name,
        // shown against the transaction in the Paystack dashboard.
        metadata.put("customerName", request.getPhoneNumber() + " - " + SITE_PREFIX);

        // FIX: capture the Paystack init response so we can return the
        // authorization_url — without it the frontend has nothing to
        // redirect the customer to for approval/payment.
        Map<String, Object> paystackData = paystackService.initiateTransaction(
                guestEmail,
                chargeAmountGhc,
                reference,
                metadata
        );

        Order order = Order.builder()
                .phoneNumber(request.getPhoneNumber())
                .network(request.getNetwork())
                .capacityGb(request.getCapacityGb())
                .costPriceGhc(basePriceGhc)
                .sellingPriceGhc(basePriceGhc)
                .paymentMethod(Order.PaymentMethod.PAYSTACK)
                .paystackRef(reference)
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
                .authorizationUrl((String) paystackData.get("authorization_url"))
                .amountGhc(chargeAmountGhc)
                .amountPesewas(paystackService.toSmallestUnit(chargeAmountGhc))
                .email(guestEmail)
                .phoneNumber(request.getPhoneNumber())
                .network(request.getNetwork().name())
                .capacityGb(request.getCapacityGb())
                .build();
    }

    // ── Guest checkout: step 2 — webhook fulfilment ───────────────────────────

    /**
     * Called by WebhookController after HMAC-SHA512 validation. Idempotent —
     * duplicate Paystack references are silently ignored.
     *
     * FIX: no longer a single @Transactional spanning the provisioning call.
     * The VERIFIED write is its own short transaction; provisioning and
     * commission happen with no transaction open; failure handling is its
     * own short transaction.
     */
    public void fulfilPaystackOrder(String paystackRef) {
        if (processedRefRepository.existsByReference(paystackRef)) {
            log.warn("[ORDER] Duplicate Paystack reference ignored: ref={}", paystackRef);
            return;
        }

        Order order = markPaystackOrderVerified(paystackRef);

        try {
            bigDreamsService.purchase(order);
            // Guest orders have no user → processCommission skips silently.
            affiliateCommissionService.processCommission(order);
        } catch (UpstreamApiException ex) {
            log.error("[ORDER] Bundle provision failed after Paystack payment: orderId={} ref={} error={}",
                    order.getId(), paystackRef, ex.getMessage());
            markOrderFailedAfterPaystackFailure(order);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected Order markPaystackOrderVerified(String paystackRef) {
        Order order = orderRepository.findByPaystackRef(paystackRef)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found for Paystack ref: " + paystackRef));

        order.setStatus(Order.OrderStatus.VERIFIED);
        orderRepository.save(order);

        processedRefRepository.save(ProcessedRef.builder()
                .reference(paystackRef)
                .eventType("GUEST_ORDER")
                .build());

        log.info("[ORDER] Paystack order VERIFIED: orderId={} ref={}", order.getId(), paystackRef);
        return order;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markOrderFailedAfterPaystackFailure(Order order) {
        order.setStatus(Order.OrderStatus.FAILED);
        orderRepository.save(order);

        if (order.getUser() != null) {
            notificationService.sendOrderFailedAlert(
                    order.getUser().getEmail(), order.getUser().getFullName(),
                    order.getId());
        }
    }

    // ── Wallet top-up: initiate ───────────────────────────────────────────────

    /**
     * A top-up funds the user's own wallet — there is no recipient line
     * involved, so the payer email is the user's real account email rather
     * than a number-derived address.
     */
    @Transactional
    public TopUpInitiateResponse initiateTopUp(UUID userId, TopUpInitiateRequest request) {
        User   user      = findUserOrThrow(userId);
        // Reference is prefixed with the site domain so it's immediately
        // recognizable in the Paystack dashboard, e.g. "databaygh.shop-A1B2C3".
        String reference = SITE_PREFIX + "-" + paystackService.generateReference();

        // The amount that actually lands in the wallet once payment is verified.
        BigDecimal baseAmountGhc = request.getAmount();
        // The amount the customer is charged via Paystack, including the 10% fee.
        BigDecimal chargeAmountGhc = addPaystackCharge(baseAmountGhc);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type",          "WALLET_TOPUP");
        metadata.put("userId",        userId.toString());
        metadata.put("baseAmountGhc", baseAmountGhc.toPlainString());
        // Human-readable customer label combining the user's name + site name,
        // shown against the transaction in the Paystack dashboard.
        metadata.put("customerName", user.getFullName() + " - " + SITE_PREFIX);

        Map<String, Object> paystackData = paystackService.initiateTransaction(
                user.getEmail(),
                chargeAmountGhc,
                reference,
                metadata
        );

        log.info("[ORDER] Wallet top-up initiated: userId={} baseAmount={} chargeAmount={} ref={}",
                userId, baseAmountGhc, chargeAmountGhc, reference);

        return TopUpInitiateResponse.builder()
                .paystackReference(reference)
                .amountGhc(chargeAmountGhc)
                .amountPesewas(paystackService.toSmallestUnit(chargeAmountGhc))
                .email(user.getEmail())
                .authorizationUrl((String) paystackData.get("authorization_url"))
                .build();
    }

    // ── Wallet top-up: webhook credit ─────────────────────────────────────────

    /**
     * @param amountGhc MUST be the BASE (pre-charge) top-up amount, read by
     *                  WebhookController from Paystack metadata key
     *                  "baseAmountGhc" — NOT the raw amount Paystack reports
     *                  as paid, which includes the 10% processing charge.
     */
    @Transactional
    public void processTopUpWebhook(UUID userId, BigDecimal amountGhc, String paystackRef) {
        if (processedRefRepository.existsByReference(paystackRef)) {
            log.warn("[ORDER] Duplicate top-up reference ignored: ref={}", paystackRef);
            return;
        }

        walletService.credit(userId, amountGhc, TransactionType.TOPUP,
                "Wallet top-up via Paystack", paystackRef);

        processedRefRepository.save(ProcessedRef.builder()
                .reference(paystackRef)
                .eventType("WALLET_TOPUP")
                .build());

        log.info("[ORDER] Wallet top-up credited: userId={} amount={} ref={}",
                userId, amountGhc, paystackRef);
    }

    // ── Wallet top-up: manual verify fallback ─────────────────────────────────

    @Transactional
    public WalletResponse verifyTopUp(UUID userId, TopUpVerifyRequest request) {
        if (processedRefRepository.existsByReference(request.getPaystackRef())) {
            log.info("[ORDER] Top-up already processed: ref={}", request.getPaystackRef());
            return WalletResponse.builder()
                    .userId(userId)
                    .balance(walletService.getBalance(userId))
                    .build();
        }

        Map<String, Object> txData         = paystackService.verifyTransaction(
                request.getPaystackRef());
        BigDecimal          chargedAmountGhc = paystackService.extractAmountGhc(txData);
        // Paystack reports the charged amount (base × 1.10) — back out the fee
        // so the wallet is only credited with the original top-up amount.
        BigDecimal          baseAmountGhc    = removePaystackCharge(chargedAmountGhc);

        walletService.credit(userId, baseAmountGhc, TransactionType.TOPUP,
                "Wallet top-up (manual verify)", request.getPaystackRef());

        processedRefRepository.save(ProcessedRef.builder()
                .reference(request.getPaystackRef())
                .eventType("WALLET_TOPUP")
                .build());

        log.info("[ORDER] Manual top-up verify success: userId={} chargedAmount={} creditedAmount={} ref={}",
                userId, chargedAmountGhc, baseAmountGhc, request.getPaystackRef());

        return WalletResponse.builder()
                .userId(userId)
                .balance(walletService.getBalance(userId))
                .build();
    }

    // ── User wallet order ─────────────────────────────────────────────────────

    /**
     * Places a wallet-funded data bundle order for a regular user.
     *
     * Price resolution: if the user was referred by a reseller
     * (User.referredByReseller), they pay that reseller's custom price for this
     * exact bundle if one exists, falling back to the admin's public price
     * otherwise. Users with no referring reseller always pay the admin's public
     * price. See PricingService.resolvePriceForUser for the shared rule.
     *
     * No Paystack processing charge applies here — that 10% was already
     * collected from the customer when the wallet was topped up. Nothing in
     * this flow reaches Paystack, so no payer email is built.
     *
     * FIX: this method is no longer @Transactional. Each DB write (debit,
     * order-save, failure-handling) is its own short REQUIRES_NEW
     * transaction that commits and releases its connection immediately.
     * bigDreamsService.purchase() and affiliateCommissionService.
     * processCommission() are called with NO transaction open on this
     * thread — a slow commission check can no longer roll back the order.
     */
    public OrderResponse placeWalletOrder(UUID userId, WalletOrderRequest request) {
        User user = findUserOrThrow(userId);
        PlatformSettings settings = getActiveSettings(request.getNetwork(), request.getCapacityGb());

        // Reseller-referred users pay their referring reseller's price for this
        // bundle (falling back to admin public price if unset); everyone else
        // pays admin's public price.
        BigDecimal price = pricingService.resolvePriceForUser(user, settings);

        rejectIfDuplicate(userId, request.getPhoneNumber(), request.getNetwork(),
                request.getCapacityGb(), "USER");

        // 1. Debit wallet — own transaction via WalletService
        walletService.debit(userId, price, TransactionType.PURCHASE,
                "Data bundle " + request.getCapacityGb() + "GB " + request.getNetwork(), null);

        // 2. Save order PENDING — own short transaction, commits immediately
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

        // 3. Provision + commission — NO transaction open on this thread.
        try {
            bigDreamsService.purchase(order);                       // own REQUIRES_NEW, commits
            affiliateCommissionService.processCommission(order);     // own REQUIRES_NEW, commits
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
    }

    // ── Reseller wallet order ─────────────────────────────────────────────────

    /**
     * Places a wallet-funded data bundle order for a reseller (wholesale price).
     *
     * No Paystack processing charge applies here — that 10% was already
     * collected from the reseller when their wallet was topped up.
     *
     * FIX: same pattern as placeWalletOrder — no top-level @Transactional,
     * each DB write is its own short REQUIRES_NEW transaction, and
     * provisioning/commission run with no transaction open on this thread.
     */
    public OrderResponse placeResellerWalletOrder(UUID userId, WalletOrderRequest request,
                                                  BigDecimal sellingPriceGhc) {
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
            bigDreamsService.purchase(order);
            affiliateCommissionService.processCommission(order);
        } catch (UpstreamApiException ex) {
            log.error("[ORDER] Big Dreams provision failed for reseller order: " +
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
    public OrderResponse getOrderStatusByRef(String paystackRef) {
        Order order = orderRepository.findByPaystackRef(paystackRef)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found for ref: " + paystackRef));
        return toOrderResponse(order);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Build the email address sent to Paystack as the payer identity, e.g.
     * "0592451539@databaygh.shop". The input is the RECIPIENT phone number from
     * the order request — the line the bundle is being delivered to — not the
     * account holder's number. Non-digit characters (spaces, "+", dashes) are
     * stripped so the local part is always a valid address component.
     */
    private String buildPayerEmail(String phoneNumber) {
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            digits = "guest";
        }
        return digits + "@" + SITE_PREFIX;
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

    // ── Paystack charge helpers ──────────────────────────────────────────────

    /** Adds the 10% processing charge on top of a base amount (e.g. 6.00 → 6.60). */
    private BigDecimal addPaystackCharge(BigDecimal baseAmountGhc) {
        return baseAmountGhc
                .multiply(BigDecimal.ONE.add(PAYSTACK_CHARGE_RATE))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Reverses the 10% processing charge to recover the base amount (e.g. 6.60 → 6.00). */
    private BigDecimal removePaystackCharge(BigDecimal chargedAmountGhc) {
        return chargedAmountGhc
                .divide(BigDecimal.ONE.add(PAYSTACK_CHARGE_RATE), 2, RoundingMode.HALF_UP);
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