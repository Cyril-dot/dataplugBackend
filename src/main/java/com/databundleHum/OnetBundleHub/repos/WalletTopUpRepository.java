package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.WalletTopUp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletTopUpRepository extends JpaRepository<WalletTopUp, Long> {
    Optional<WalletTopUp> findByGatewayRef(String gatewayRef);
}
