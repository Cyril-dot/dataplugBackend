package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.WalletTopUp;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WalletTopUpRepository extends JpaRepository<WalletTopUp, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WalletTopUp> findByGatewayRef(String gatewayRef);

    List<WalletTopUp> findByStatus(WalletTopUp.Status status);
}
