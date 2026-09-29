package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // ── Single order lookup ───────────────────────────────────────────────────

    Optional<Order> findByPaystackRef(String paystackRef);

    Optional<Order> findByKorapayRefundReference(String korapayRefundReference);

    /** Finds the latest provider rejection for a recipient awaiting MTN approval. */
    Optional<Order> findFirstByUserIdAndPhoneNumberAndNetworkAndStatusAndFailureReasonContainingOrderByCreatedAtDesc(
            UUID userId,
            String phoneNumber,
            PlatformSettings.Network network,
            Order.OrderStatus status,
            String failureReasonFragment
    );

    /** Look up orders by Big Dreams Data reference — used for reconciliation. */
    List<Order> findByDbhReference(String dbhReference);

    // ── User order history ────────────────────────────────────────────────────

    Page<Order> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    Page<Order> findByUserAndOrderedByRoleOrderByCreatedAtDesc(
            User user, Order.OrderedByRole role, Pageable pageable);

    /** Total orders placed by a user regardless of role — used for sub-customer stats. */
    long countByUser(User user);

    long countByUserAndOrderedByRole(User user, Order.OrderedByRole role);

    // ── Status queries ────────────────────────────────────────────────────────

    /** All orders in a given status — used by BigDreamsService background poller. */
    List<Order> findByStatus(Order.OrderStatus status);

    /** Paginated failed orders for the Super Admin order review. */
    Page<Order> findByStatus(Order.OrderStatus status, Pageable pageable);

    @Query("SELECT o FROM Order o WHERE LOWER(o.korapayRefundStatus) = 'success' "
            + "OR (o.status = :failedStatus AND o.paymentMethod = :walletMethod)")
    Page<Order> findRefundedOrders(@Param("failedStatus") Order.OrderStatus failedStatus,
                                   @Param("walletMethod") Order.PaymentMethod walletMethod,
                                   Pageable pageable);

    @Query("SELECT o FROM Order o WHERE LOWER(o.korapayRefundStatus) IN ('pending', 'requested', 'processing')")
    Page<Order> findOrdersWithPendingRefund(Pageable pageable);

    @Query("SELECT o FROM Order o WHERE LOWER(o.korapayRefundStatus) IN ('failed', 'refund_request_failed')")
    Page<Order> findOrdersWithRefundIssue(Pageable pageable);

    /** Sum of selling_price_ghc for orders in a given status — platform revenue KPI. */
    @Query("SELECT COALESCE(SUM(o.sellingPriceGhc), 0) FROM Order o WHERE o.status = :status")
    BigDecimal sumSellingPriceByStatus(@Param("status") Order.OrderStatus status);

    // ── Duplicate-order protection (Layer 1) ──────────────────────────────────

    /**
     * Returns true if a non-FAILED order for the same user + phone + network +
     * capacity already exists within the given time window.
     * Called by OrderService.rejectIfDuplicate() before any wallet debit.
     */
    boolean existsByUserIdAndPhoneNumberAndNetworkAndCapacityGbAndStatusNotAndCreatedAtAfter(
            UUID userId,
            String phoneNumber,
            PlatformSettings.Network network,
            BigDecimal capacityGb,
            Order.OrderStatus excludedStatus,
            LocalDateTime after
    );

    @Query("SELECT SUM(o.costPriceGhc) FROM Order o WHERE o.status = :status")
    BigDecimal sumCostPriceByStatus(@Param("status") Order.OrderStatus status);
}
