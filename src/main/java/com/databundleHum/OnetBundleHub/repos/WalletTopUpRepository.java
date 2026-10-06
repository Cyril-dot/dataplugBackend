package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.WalletTopUp;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletTopUpRepository extends JpaRepository<WalletTopUp, Long> {
    Optional<WalletTopUp> findByGatewayRef(String gatewayRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WalletTopUp> findByGatewayRefForUpdate(String gatewayRef);
}
