package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.PlatformSettings;
import com.databundleHum.OnetBundleHub.entity.UnverifiedRecipient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnverifiedRecipientRepository extends JpaRepository<UnverifiedRecipient, Long> {
    Optional<UnverifiedRecipient> findByPhoneNumberAndNetwork(String phoneNumber, PlatformSettings.Network network);
    List<UnverifiedRecipient> findByLastFailedAtBetweenOrderByLastFailedAtDesc(LocalDateTime from, LocalDateTime to);

    List<UnverifiedRecipient> findBySubmittedByIdOrderByLastFailedAtDesc(UUID userId);
}
