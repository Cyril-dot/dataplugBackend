package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.PaystackWithdrawal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaystackWithdrawalRepository extends JpaRepository<PaystackWithdrawal, Long> {
    Optional<PaystackWithdrawal> findByReference(String reference);
    Page<PaystackWithdrawal> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<PaystackWithdrawal> findByStatusOrderByCreatedAtDesc(PaystackWithdrawal.WithdrawalStatus status, Pageable pageable);
    List<PaystackWithdrawal> findByStatusInAndUpdatedAtBefore(
            Collection<PaystackWithdrawal.WithdrawalStatus> statuses, LocalDateTime before);
}
