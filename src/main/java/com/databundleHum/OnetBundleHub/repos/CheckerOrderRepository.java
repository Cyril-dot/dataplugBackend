package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.CheckerOrder;
import com.databundleHum.OnetBundleHub.entity.CheckerPricing;
import com.databundleHum.OnetBundleHub.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface CheckerOrderRepository extends JpaRepository<CheckerOrder, Long> {

    Optional<CheckerOrder> findByGatewayRef(String gatewayRef);

    Page<CheckerOrder> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    boolean existsByUserIdAndPhoneNumberAndExamTypeAndStatusNotAndCreatedAtAfter(
            UUID userId, String phoneNumber, CheckerPricing.ExamType examType,
            CheckerOrder.CheckerOrderStatus notStatus, LocalDateTime createdAfter);
}