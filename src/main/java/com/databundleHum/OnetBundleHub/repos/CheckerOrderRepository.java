package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.CheckerOrder;
import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import com.databundleHum.OnetBundleHub.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckerOrderRepository extends JpaRepository<CheckerOrder, Long> {

    Optional<CheckerOrder> findByGatewayRef(String gatewayRef);

    Page<CheckerOrder> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    boolean existsByUserIdAndPhoneNumberAndExamTypeAndStatusNotAndCreatedAtAfter(
            UUID userId, String phoneNumber, CheckerPricing.ExamType examType,
            CheckerOrder.CheckerOrderStatus notStatus, LocalDateTime createdAfter);

    /**
     * Orders paid for (VERIFIED) but never actually provisioned a code —
     * i.e. stuck. Used by the admin "stuck orders" endpoint and by the
     * scheduled reconciliation job in CheckerService.
     */
    List<CheckerOrder> findByStatusOrderByCreatedAtAsc(CheckerOrder.CheckerOrderStatus status);
}