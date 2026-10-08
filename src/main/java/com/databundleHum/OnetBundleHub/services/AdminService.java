package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.dtos.*;
import com.databundleHum.OnetBundleHub.dtos.AdminResellerResponse;
import com.databundleHum.OnetBundleHub.dtos.response.*;
import com.databundleHum.OnetBundleHub.entity.*;
import com.databundleHum.OnetBundleHub.entity.WalletTransaction.TransactionType;
import com.databundleHum.OnetBundleHub.repos.*;
import com.databundleHum.OnetBundleHub.security.*;
import com.databundleHum.OnetBundleHub.util.DataPackBranding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Super Admin service layer.
 *
 * Covers:
 *  - Platform dashboard KPIs
 *  - User management (list, activate/deactivate)
 *  - Reseller application review (approve / reject + auto-refund on rejection)
 *  - Payout processing (mark PAID / reject) — now handles BOTH reseller profit
 *    payouts and affiliate commission payouts, distinguished by Payout.source
 *  - Platform pricing management (public + reseller prices per network/GB)
 *  - All-orders and all-transactions views
 *  - Slug backfill for approved resellers missing a store slug
 *
 * Changes from previous version:
 *  - approveReseller() now generates a unique store slug via
 *    ResellerServiceImpl.generateUniqueSlug() and persists it on the profile.
 *  - approveReseller() accepts an optional slug override in the request body.
 *  - markPayoutPaid() now decrements ResellerProfile.profitPaidGhc instead of
 *    debiting the personal wallet — reseller profit and wallet are separate balances
 *    per architecture §3.7 and §9.
 *  - backfillMissingSlugs() added to recover approved profiles with null store_slug
 *    (e.g. approved before slug generation logic existed).
 *  - getDashboard() now also surfaces totalAffiliateEarningsLiabilityGhc — the
 *    total unclaimed affiliate commission across all users, kept separate
 *    from totalWalletLiabilitiesGhc since they're different pots of money.
 *  - markPayoutPaid() / rejectPayout() now branch on Payout.source:
 *      RESELLER_PROFIT      → touches ResellerProfile.profitPaidGhc (as before)
 *      AFFILIATE_COMMISSION → the payout amount was already reserved (deducted
 *                             from User.affiliateEarningsGhc) at request time
 *                             in AffiliateService.requestPayout(), so marking
 *                             PAID does nothing further, and REJECTING must
 *                             refund the reserved amount back to the affiliate.
 *
 * ── REVENUE/PROFIT FIX (2026-07-23) ──────────────────────────────────────────
 *
 * getDashboard() previously called:
 *     orderRepository.sumSellingPriceByStatus(Order.OrderStatus.DELIVERED)
 *
 * But nothing in OrderService (guest checkout, user wallet order, reseller
 * wallet order) ever sets an order's status to DELIVERED — the only statuses
 * actually reachable are PENDING, VERIFIED, and FAILED (see OrderService:
 * markPaystackOrderVerified, placeWalletOrder, placeResellerWalletOrder).
 * DELIVERED is dead code as far as status transitions go, so this query
 * always summed zero rows and totalRevenueGhc was permanently stuck at 0,
 * regardless of how many real sales happened.
 *
 * THE FIX: revenue/profit are now computed from Order.OrderStatus.VERIFIED,
 * which is the actual "payment confirmed and bundle provisioning
 * attempted/succeeded" status used across the codebase. Profit is now also
 * exposed as (revenue − cost), using the new
 * orderRepository.sumCostPriceByStatus(...) query. If your Order entity later
 * grows a true terminal DELIVERED state distinct from VERIFIED, swap the
 * status constant used below — but as of this fix, VERIFIED is the only
 * status that correlates with "sale actually happened."
 *
 * NOTE: this requires two repository methods on OrderRepository that must
 * exist (or be added) alongside the existing sumSellingPriceByStatus:
 *     BigDecimal sumSellingPriceByStatus(Order.OrderStatus status);
 *     BigDecimal sumCostPriceByStatus(Order.OrderStatus status);
 * e.g.:
 *     @Query("SELECT SUM(o.costPriceGhc) FROM Order o WHERE o.status = :status")
 *     BigDecimal sumCostPriceByStatus(@Param("status") Order.OrderStatus status);
 *
 * ── ADMIN ORDER VIEW FIX (2026-07-23) ─────────────────────────────────────────
 *
 * toOrderResponse() previously left userEmail, profitGhc, and
 * resellerStoreName unset even though OrderResponse already had fields for
 * them. Admins reviewing /admin/orders had no way to see which account
 * placed an order — only the destination phone number and bundle size.
 * toOrderResponse() now populates:
 *   - userEmail: order.getUser().getEmail(), or null for guest orders
 *   - userPhone: order.getUser().getPhone(), or null for guest orders — the
 *     BUYER's own account phone, distinct from o.getPhoneNumber() (the
 *     destination number the bundle is delivered to). A buyer can order
 *     data for a number that isn't their own (e.g. topping up a friend or
 *     family member), so admins need both numbers visible, not just one.
 *   - profitGhc: sellingPriceGhc − costPriceGhc
 *   - resellerStoreName: populated when the order was placed by a RESELLER
 *     and a reseller profile with a store name/slug exists for that user
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository              userRepository;
    private final OrderRepository             orderRepository;
    private final ResellerProfileRepository   resellerProfileRepository;
    private final PayoutRepository            payoutRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PlatformSettingsRepository  platformSettingsRepository;
    private final WalletService               walletService;
    private final NotificationService         notificationService;
    private final ResellerServiceImpl         resellerService; // for generateUniqueSlug()

    /**
     * The status that represents "payment confirmed / sale actually happened"
     * for revenue and profit reporting purposes. See REVENUE/PROFIT FIX note
     * above for why this is VERIFIED and not DELIVERED.
     */
    private static final Order.OrderStatus REVENUE_STATUS = Order.OrderStatus.VERIFIED;

    // ── Dashboard ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AdminDashboardResponse getDashboard() {
        long totalUsers     = userRepository.count();
        long totalResellers = userRepository.countByRole(User.Role.RESELLER);
        long pendingApps    = resellerProfileRepository
                .countByStatus(ResellerProfile.ResellerStatus.PENDING);
        long totalOrders    = orderRepository.count();
        long pendingPayouts = payoutRepository.countByStatus(Payout.PayoutStatus.PENDING);

        // FIX: was summing Order.OrderStatus.DELIVERED, a status no order ever
        // reaches — always returned 0. Now sums VERIFIED, the real
        // "payment confirmed" status. See class-level REVENUE/PROFIT FIX note.
        BigDecimal totalRevenue = orderRepository.sumSellingPriceByStatus(REVENUE_STATUS);
        if (totalRevenue == null) totalRevenue = BigDecimal.ZERO;

        // Total cost of goods for the same set of orders, so profit can be derived.
        BigDecimal totalCost = orderRepository.sumCostPriceByStatus(REVENUE_STATUS);
        if (totalCost == null) totalCost = BigDecimal.ZERO;

        // Gross profit = revenue − cost. This does NOT subtract affiliate
        // commissions paid out — those are tracked separately below as a
        // liability (totalAffiliateEarningsLiabilityGhc), since commission
        // owed doesn't necessarily mean commission has been cashed out yet.
        BigDecimal totalProfit = totalRevenue.subtract(totalCost);

        BigDecimal walletLiabilities = userRepository.sumWalletBalances();
        if (walletLiabilities == null) walletLiabilities = BigDecimal.ZERO;

        BigDecimal pendingPayoutsTotal = payoutRepository.sumAmountByStatus(Payout.PayoutStatus.PENDING);
        if (pendingPayoutsTotal == null) pendingPayoutsTotal = BigDecimal.ZERO;

        // Total unclaimed affiliate commission across all users. This is a
        // SEPARATE liability from walletLiabilities — do not add them together.
        BigDecimal affiliateEarningsLiability = userRepository.sumAffiliateEarningsGhc();
        if (affiliateEarningsLiability == null) affiliateEarningsLiability = BigDecimal.ZERO;

        log.debug("Admin dashboard fetched: users={} orders={} revenue={} cost={} profit={}",
                totalUsers, totalOrders, totalRevenue, totalCost, totalProfit);

        return AdminDashboardResponse.builder()
                .totalUsers(totalUsers)
                .totalResellers(totalResellers)
                .pendingResellerApplications(pendingApps)
                .totalOrders(totalOrders)
                .pendingPayouts(pendingPayouts)
                .totalRevenueGhc(totalRevenue)
                .totalCostGhc(totalCost)
                .totalProfitGhc(totalProfit)
                .totalWalletLiabilitiesGhc(walletLiabilities)
                .totalPendingPayoutsGhc(pendingPayoutsTotal)
                .totalAffiliateEarningsLiabilityGhc(affiliateEarningsLiability)
                .build();
    }

    // ── User management ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<AdminUserResponse> getUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(this::toUserResponse);
    }

    @Transactional(readOnly = true)
    public AdminUserResponse getUser(UUID userId) {
        return toUserResponse(findUserOrThrow(userId));
    }

    @Transactional
    public AdminUserResponse adjustUserWallet(UUID adminId, UUID targetUserId,
                                              AdminWalletAdjustmentRequest request) {
        String reason = request.getReason().trim();
        boolean credit = request.getAction() == AdminWalletAdjustmentRequest.Action.CREDIT;
        TransactionType transactionType = credit ? TransactionType.ADMIN_CREDIT : TransactionType.ADMIN_DEBIT;
        String action = credit ? "credit" : "debit";
        String description = "Admin wallet " + action + " by adminId=" + adminId + ". Reason: " + reason;
        String reference = "ADMIN_WALLET_" + request.getAction().name() + "_" + UUID.randomUUID();

        if (credit) {
            walletService.credit(targetUserId, request.getAmount(), transactionType, description, reference);
        } else {
            walletService.debit(targetUserId, request.getAmount(), transactionType, description, reference);
        }

        BigDecimal updatedBalance = walletService.getBalance(targetUserId);
        log.info("Admin wallet {} completed: adminId={} targetUserId={} amount={} balanceAfter={} reference={}",
                action, adminId, targetUserId, request.getAmount(), updatedBalance, reference);
        return toUserResponse(findUserOrThrow(targetUserId));
    }

    @Transactional
    public AdminUserResponse setUserActive(UUID adminId, UUID targetUserId, boolean active) {
        User target = findUserOrThrow(targetUserId);
        target.setActive(active);
        userRepository.save(target);
        log.info("Admin userId={} set userId={} active={}", adminId, targetUserId, active);
        return toUserResponse(target);
    }

    // ── Reseller management ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<AdminResellerResponse> getResellers(Pageable pageable) {
        return resellerProfileRepository.findAll(pageable).map(this::toResellerResponse);
    }

    @Transactional(readOnly = true)
    public Page<AdminResellerResponse> getResellersByStatus(
            ResellerProfile.ResellerStatus status, Pageable pageable) {
        return resellerProfileRepository.findByStatus(status, pageable)
                .map(this::toResellerResponse);
    }

    /**
     * Approve a reseller application.
     *
     * Steps:
     *  1. Validate the profile is still PENDING.
     *  2. Generate a unique slug from the reseller's full name (or use the
     *     admin-supplied slug override from the request).
     *  3. Set profile.status → APPROVED, profile.storeSlug, profile.approvedAt.
     *  4. Set user.role → RESELLER.
     *  5. Send approval email with the store link.
     *
     * The slug is immutable once set — admins cannot change it after approval
     * because live store links and QR codes would break.
     *
     * @param request  may include an optional {@code slugOverride}; if null or
     *                 blank, the slug is auto-generated from the user's name.
     */
    @Transactional
    public AdminResellerResponse approveReseller(UUID adminId, UUID profileId,
                                                 AdminResellerActionRequest request) {
        ResellerProfile profile = findProfileOrThrow(profileId);

        if (profile.getStatus() != ResellerProfile.ResellerStatus.PENDING) {
            throw new ValidationException(
                    "Cannot approve application with status: " + profile.getStatus());
        }

        User admin    = findUserOrThrow(adminId);
        User reseller = profile.getUser();

        // ── Slug generation ───────────────────────────────────────────────────
        String slug;
        if (request.getSlugOverride() != null && !request.getSlugOverride().isBlank()) {
            // Admin-supplied override — still validate uniqueness
            slug = request.getSlugOverride().trim().toLowerCase();
            if (resellerProfileRepository.existsByStoreSlug(slug)) {
                throw new ValidationException(
                        "The slug '" + slug + "' is already taken. Choose another.");
            }
        } else {
            slug = resellerService.generateUniqueSlug(reseller.getFullName());
        }

        profile.setStatus(ResellerProfile.ResellerStatus.APPROVED);
        profile.setStoreSlug(slug);
        profile.setApprovedAt(LocalDateTime.now());
        profile.setApprovedBy(admin);
        if (request.getNote() != null) profile.setApplicationNote(request.getNote());
        resellerProfileRepository.save(profile);

        reseller.setRole(User.Role.RESELLER);
        userRepository.save(reseller);

        log.info("Reseller APPROVED: profileId={} userId={} slug={} by adminId={}",
                profileId, reseller.getId(), slug, adminId);

        return toResellerResponse(profile);
    }

    /**
     * Reject a reseller application.
     * Refunds the GHS 20 registration fee back to the applicant's wallet.
     */
    @Transactional
    public AdminResellerResponse rejectReseller(UUID adminId, UUID profileId,
                                                AdminResellerActionRequest request) {
        ResellerProfile profile = findProfileOrThrow(profileId);

        if (profile.getStatus() != ResellerProfile.ResellerStatus.PENDING) {
            throw new ValidationException(
                    "Cannot reject application with status: " + profile.getStatus());
        }

        User reseller = profile.getUser();

        profile.setStatus(ResellerProfile.ResellerStatus.REJECTED);
        profile.setRejectionReason(request.getNote());
        resellerProfileRepository.save(profile);

        // Refund GHS 20 registration fee
        walletService.credit(reseller.getId(), new BigDecimal("20.00"),
                TransactionType.REFUND,
                "Reseller registration fee refund — application rejected",
                "RESELLER_REJECT_" + profileId);

        notificationService.sendResellerRejectedEmail(
                reseller.getEmail(), reseller.getFullName(), request.getNote());

        log.info("Reseller REJECTED: profileId={} userId={} by adminId={} reason={}",
                profileId, reseller.getId(), adminId, request.getNote());

        return toResellerResponse(profile);
    }

    // ── Slug backfill ─────────────────────────────────────────────────────────

    /**
     * Backfill store slugs for any APPROVED reseller profiles that are missing one.
     *
     * This covers resellers who were approved before slug generation was introduced.
     * Safe to call multiple times — only profiles with a null store_slug are touched.
     *
     * Expose via an admin-only endpoint, e.g.:
     *   POST /admin/resellers/backfill-slugs
     *
     * @return number of profiles that were updated
     */
    @Transactional
    public int backfillMissingSlugs() {
        List<ResellerProfile> profiles = resellerProfileRepository
                .findByStatusAndStoreSlugIsNull(ResellerProfile.ResellerStatus.APPROVED);

        if (profiles.isEmpty()) {
            log.info("[SLUG BACKFILL] No profiles found with missing slugs.");
            return 0;
        }

        int count = 0;
        for (ResellerProfile profile : profiles) {
            String slug = resellerService.generateUniqueSlug(profile.getUser().getFullName());
            profile.setStoreSlug(slug);
            resellerProfileRepository.save(profile);
            log.info("[SLUG BACKFILL] Set slug='{}' for profileId={} userId={}",
                    slug, profile.getId(), profile.getUser().getId());
            count++;
        }

        log.info("[SLUG BACKFILL] Complete. {} profile(s) updated.", count);
        return count;
    }

    // ── Payout management ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<PayoutResponse> getPayouts(Payout.PayoutStatus status, Pageable pageable) {
        if (status != null) {
            return payoutRepository.findByStatusOrderByCreatedAtAsc(status, pageable)
                    .map(this::toPayoutResponse);
        }
        return payoutRepository.findAll(pageable).map(this::toPayoutResponse);
    }

    /**
     * Mark a payout as PAID.
     *
     * Behaviour depends on Payout.source:
     *
     *   RESELLER_PROFIT — architecture §3.7 / §9: reseller profit balance and
     *   personal wallet are separate. We increment profitPaidGhc on the
     *   ResellerProfile (reducing their available profit balance). The
     *   personal wallet is NOT touched. Availability is checked here because
     *   the reseller flow does not reserve funds at request time.
     *
     *   AFFILIATE_COMMISSION — AffiliateService.requestPayout() already
     *   deducted (reserved) the amount from User.affiliateEarningsGhc at
     *   request time, so there is nothing further to debit here. We only
     *   flip the status to PAID.
     */
    @Transactional
    public PayoutResponse markPayoutPaid(UUID adminId, Long payoutId,
                                         AdminPayoutPaidRequest request) {
        Payout payout = findPayoutOrThrow(payoutId);

        if (payout.getStatus() != Payout.PayoutStatus.PENDING &&
                payout.getStatus() != Payout.PayoutStatus.PROCESSING) {
            throw new ValidationException(
                    "Payout cannot be marked PAID. Current status: " + payout.getStatus());
        }

        User admin = findUserOrThrow(adminId);

        if (payout.getSource() == Payout.PayoutSource.RESELLER_PROFIT) {
            // Increment the paid-out amount on the reseller's profit balance
            ResellerProfile profile = resellerProfileRepository
                    .findByUser(payout.getReseller())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No reseller profile found for payout reseller."));

            BigDecimal available = profile.getAvailableProfitGhc();
            if (available.compareTo(payout.getAmount()) < 0) {
                throw new InsufficientBalanceException(
                        "Reseller's available profit (GHS " + available
                                + ") is less than payout amount (GHS " + payout.getAmount() + ").");
            }

            profile.setProfitPaidGhc(profile.getProfitPaidGhc().add(payout.getAmount()));
            resellerProfileRepository.save(profile);
        }
        // AFFILIATE_COMMISSION: amount was already reserved out of
        // affiliateEarningsGhc at request time — nothing more to debit.

        payout.setStatus(Payout.PayoutStatus.PAID);
        payout.setPaidAt(LocalDateTime.now());
        payout.setPaidBy(admin);
        payout.setAdminNote(request.getAdminNote());
        payoutRepository.save(payout);

        notificationService.sendPayoutPaidAlert(
                payout.getReseller().getEmail(),
                payout.getReseller().getFullName(),
                payout.getAmount());

        log.info("Payout PAID: payoutId={} source={} recipientId={} amount={} by adminId={}",
                payoutId, payout.getSource(), payout.getReseller().getId(),
                payout.getAmount(), adminId);

        return toPayoutResponse(payout);
    }

    /**
     * Reject a payout request.
     *
     *   RESELLER_PROFIT — no balance mutation needed: the reseller flow never
     *   reserved the amount at request time, so nothing to give back.
     *
     *   AFFILIATE_COMMISSION — the amount WAS reserved (deducted from
     *   affiliateEarningsGhc) at request time, so rejecting must refund it
     *   back to the affiliate, or that commission would simply vanish.
     */
    @Transactional
    public PayoutResponse rejectPayout(UUID adminId, Long payoutId,
                                       AdminPayoutRejectRequest request) {
        Payout payout = findPayoutOrThrow(payoutId);

        if (payout.getStatus() != Payout.PayoutStatus.PENDING) {
            throw new ValidationException(
                    "Payout cannot be rejected. Current status: " + payout.getStatus());
        }

        if (payout.getSource() == Payout.PayoutSource.AFFILIATE_COMMISSION) {
            User affiliate = payout.getReseller(); // generic recipient field
            affiliate.setAffiliateEarningsGhc(
                    affiliate.getAffiliateEarningsGhc().add(payout.getAmount()));
            userRepository.save(affiliate);
            log.info("[AFFILIATE] Payout rejected — refunded to earnings balance: " +
                            "payoutId={} affiliateId={} amount={} newBalance={}",
                    payoutId, affiliate.getId(), payout.getAmount(),
                    affiliate.getAffiliateEarningsGhc());
        }

        payout.setStatus(Payout.PayoutStatus.REJECTED);
        payout.setAdminNote(request.getReason());
        payoutRepository.save(payout);

        notificationService.sendPayoutRejectedAlert(
                payout.getReseller().getEmail(),
                payout.getReseller().getFullName(),
                payout.getAmount(),
                request.getReason());

        log.info("Payout REJECTED: payoutId={} source={} recipientId={} by adminId={} reason={}",
                payoutId, payout.getSource(), payout.getReseller().getId(),
                adminId, request.getReason());

        return toPayoutResponse(payout);
    }

    // ── Platform pricing ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<AdminPricingResponse> getPricingTable(Pageable pageable) {
        return platformSettingsRepository.findAll(pageable).map(this::toPricingResponse);
    }

    /**
     * Upsert a pricing row.
     * Enforces: resellerPrice < publicPrice (to guarantee reseller margin).
     */
    @Transactional
    public AdminPricingResponse upsertPricing(UUID adminId, AdminPricingRequest request) {
        if (request.getResellerPriceGhc().compareTo(request.getPublicPriceGhc()) >= 0) {
            throw new ValidationException(
                    "Reseller price (GHS " + request.getResellerPriceGhc()
                            + ") must be strictly less than public price (GHS "
                            + request.getPublicPriceGhc() + ").");
        }

        PlatformSettings settings = platformSettingsRepository
                .findByNetworkAndCapacityGb(request.getNetwork(), request.getCapacityGb())
                .orElseGet(() -> PlatformSettings.builder()
                        .network(request.getNetwork())
                        .capacityGb(request.getCapacityGb())
                        .build());

        settings.setPublicPriceGhc(request.getPublicPriceGhc());
        settings.setResellerPriceGhc(request.getResellerPriceGhc());
        settings.setActive(request.isActive());
        settings = platformSettingsRepository.save(settings);

        log.info("Pricing upserted by adminId={}: network={} gb={} public={} reseller={}",
                adminId, request.getNetwork(), request.getCapacityGb(),
                request.getPublicPriceGhc(), request.getResellerPriceGhc());

        return toPricingResponse(settings);
    }

    @Transactional
    public AdminPricingResponse toggleBundleActive(UUID adminId, Long settingsId, boolean active) {
        PlatformSettings settings = platformSettingsRepository.findById(settingsId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Pricing row not found: " + settingsId));
        settings.setActive(active);
        platformSettingsRepository.save(settings);
        log.info("Bundle active={}: settingsId={} by adminId={}", active, settingsId, adminId);
        return toPricingResponse(settings);
    }

    // ── All-orders / all-transactions ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<OrderResponse> getAllOrders(Pageable pageable, String filter) {
        String normalizedFilter = filter == null ? "ALL" : filter.trim().toUpperCase(Locale.ROOT);
        Page<Order> orders = switch (normalizedFilter) {
            case "ALL" -> orderRepository.findAll(pageable);
            case "FAILED" -> orderRepository.findByStatus(Order.OrderStatus.FAILED, pageable);
            case "REFUNDED" -> orderRepository.findRefundedOrders(
                    Order.OrderStatus.FAILED, Order.PaymentMethod.WALLET, pageable);
            case "REFUND_PENDING" -> orderRepository.findOrdersWithPendingRefund(pageable);
            case "REFUND_ISSUE" -> orderRepository.findOrdersWithRefundIssue(pageable);
            default -> throw new ValidationException("Unsupported order filter: " + filter);
        };
        return orders.map(this::toOrderResponse);
    }

    @Transactional(readOnly = true)
    public Page<WalletTransactionResponse> getAllTransactions(Pageable pageable) {
        return walletTransactionRepository.findAll(pageable).map(this::toTxResponse);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    private ResellerProfile findProfileOrThrow(UUID profileId) {
        return resellerProfileRepository.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reseller profile not found: " + profileId));
    }

    private Payout findPayoutOrThrow(Long payoutId) {
        return payoutRepository.findById(payoutId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Payout not found: " + payoutId));
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private AdminUserResponse toUserResponse(User u) {
        return AdminUserResponse.builder()
                .id(u.getId())
                .fullName(u.getFullName())
                .email(u.getEmail())
                .phone(u.getPhone())
                .role(u.getRole().name())
                .walletBalance(u.getWalletBalance())
                .active(u.isActive())
                .createdAt(u.getCreatedAt())
                .build();
    }

    private AdminResellerResponse toResellerResponse(ResellerProfile p) {
        return AdminResellerResponse.builder()
                .profileId(p.getId())
                .userId(p.getUser().getId())
                .fullName(p.getUser().getFullName())
                .email(p.getUser().getEmail())
                .phone(p.getUser().getPhone())
                .status(p.getStatus().name())
                .storeSlug(p.getStoreSlug())
                .storeName(p.getEffectiveStoreName())
                .applicationNote(p.getApplicationNote())
                .rejectionReason(p.getRejectionReason())
                .totalRevenueGhc(p.getTotalRevenueGhc())
                .totalCostGhc(p.getTotalCostGhc())
                .totalProfitGhc(p.getTotalProfitGhc())
                .availableProfitGhc(p.getAvailableProfitGhc())
                .approvedAt(p.getApprovedAt())
                .createdAt(p.getCreatedAt())
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

    private AdminPricingResponse toPricingResponse(PlatformSettings s) {
        return AdminPricingResponse.builder()
                .id(s.getId())
                .network(s.getNetwork().name())
                .capacityGb(s.getCapacityGb())
                .publicPriceGhc(s.getPublicPriceGhc())
                .resellerPriceGhc(s.getResellerPriceGhc())
                .active(s.isActive())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    /**
     * Admin order-view mapper.
     *
     * FIX (2026-07-23): now populates userEmail (who placed the order),
     * userPhone (the buyer's own account phone — distinct from the
     * destination number the bundle is sent to), profitGhc (sellingPrice −
     * costPrice), and resellerStoreName (when the order was placed by a
     * reseller), all of which were previously left unset even though
     * OrderResponse already had fields for them.
     */
    private OrderResponse toOrderResponse(Order o) {
        User orderUser = o.getUser();

        // userEmail: null for guest orders (no User row at all).
        String userEmail = (orderUser != null) ? orderUser.getEmail() : null;

        // userPhone: the BUYER's own account phone number — distinct from
        // o.getPhoneNumber() (the destination number the bundle is sent to).
        // A buyer can order data for a number that isn't their own (e.g.
        // topping up a friend or family member's phone), so admins need
        // both numbers visible rather than just the destination number.
        String userPhone = (orderUser != null) ? orderUser.getPhone() : null;

        // profitGhc: sellingPrice - costPrice. Zero/negative-safe — just a
        // straight subtraction; guest and regular-user orders will typically
        // be zero since costPrice == sellingPrice for those flows.
        BigDecimal profit = (o.getSellingPriceGhc() != null && o.getCostPriceGhc() != null)
                ? o.getSellingPriceGhc().subtract(o.getCostPriceGhc())
                : BigDecimal.ZERO;

        // resellerStoreName: only meaningful when the order was placed by a
        // reseller (direct wallet order or storefront order). Look up their
        // profile's effective store name; skip the lookup entirely for guest
        // and regular USER orders to avoid an unnecessary query per row.
        String resellerStoreName = null;
        if (orderUser != null && o.getOrderedByRole() == Order.OrderedByRole.RESELLER) {
            resellerStoreName = resellerProfileRepository.findByUser(orderUser)
                    .map(ResellerProfile::getEffectiveStoreName)
                    .orElse(null);
        }

        return OrderResponse.builder()
                .id(o.getId())
                .phoneNumber(o.getPhoneNumber())
                .network(o.getNetwork().name())
                .capacityGb(o.getCapacityGb())
                .costPriceGhc(o.getCostPriceGhc())
                .sellingPriceGhc(o.getSellingPriceGhc())
                .profitGhc(profit)
                .paymentMethod(o.getPaymentMethod().name())
                .paystackRef(o.getPaystackRef())
                .korapayRefundReference(o.getKorapayRefundReference())
                .korapayRefundStatus(o.getKorapayRefundStatus())
                .status(o.getStatus().name())
                .failureReason(DataPackBranding.forDisplay(o.getFailureReason()))
                .guest(o.isGuest())
                .storefrontOrder(o.isStorefrontOrder())
                .resellerStoreName(resellerStoreName)
                .userFullName(orderUser == null ? null : orderUser.getFullName())
                .userEmail(userEmail)
                .userPhone(userPhone)
                .createdAt(o.getCreatedAt())
                .updatedAt(o.getUpdatedAt())
                .build();
    }

    private WalletTransactionResponse toTxResponse(WalletTransaction t) {
        return WalletTransactionResponse.builder()
                .id(t.getId())
                .type(t.getType().name())
                .amount(t.getAmount())
                .balanceAfter(t.getBalanceAfter())
                .reference(t.getReference())
                .description(t.getDescription())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
