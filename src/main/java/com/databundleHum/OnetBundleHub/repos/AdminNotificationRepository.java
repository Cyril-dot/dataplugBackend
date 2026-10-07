package com.databundleHum.OnetBundleHub.repos;

import com.databundleHum.OnetBundleHub.entity.AdminNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AdminNotificationRepository extends JpaRepository<AdminNotification, UUID> {
    List<AdminNotification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);
    long countByRecipientIdAndReadAtIsNull(UUID recipientId);
    boolean existsByRecipientIdAndEventKey(UUID recipientId, String eventKey);
}
