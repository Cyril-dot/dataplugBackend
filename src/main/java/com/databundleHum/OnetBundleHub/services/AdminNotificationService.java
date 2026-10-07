package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.dtos.response.AdminNotificationResponse;
import com.databundleHum.OnetBundleHub.entity.AdminNotification;
import com.databundleHum.OnetBundleHub.entity.User;
import com.databundleHum.OnetBundleHub.repos.AdminNotificationRepository;
import com.databundleHum.OnetBundleHub.repos.UserRepository;
import com.databundleHum.OnetBundleHub.security.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationService {
    private final AdminNotificationRepository notificationRepository;
    private final UserRepository userRepository;

    /**
     * Creates one notification per super-admin. The event key makes Paystack
     * webhook retries safe and prevents duplicate alerts for the same payment.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyPaystackPayment(String reference, BigDecimal amountGhc, String currency, String paymentType) {
        String eventKey = "paystack:charge.success:" + reference;
        String amount = amountGhc == null ? "an unknown amount" : "GHS " + amountGhc.setScale(2);
        String paymentLabel = paymentType == null || paymentType.isBlank() ? "payment" : paymentType.toLowerCase();
        for (User admin : userRepository.findAllByRole(User.Role.SUPER_ADMIN)) {
            if (notificationRepository.existsByRecipientIdAndEventKey(admin.getId(), eventKey)) continue;
            notificationRepository.save(AdminNotification.builder()
                    .recipient(admin)
                    .eventKey(eventKey)
                    .type("PAYMENT_RECEIVED")
                    .title("Payment received")
                    .message(amount + " " + paymentLabel + " received via Paystack. Reference: " + reference)
                    .build());
        }
        log.info("[ADMIN-NOTIFICATION] Paystack payment notification recorded: ref={} amount={} type={}",
                reference, amountGhc, paymentType);
    }

    @Transactional(readOnly = true)
    public List<AdminNotificationResponse> list(UUID adminId, int limit) {
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(adminId, PageRequest.of(0, Math.min(Math.max(limit, 1), 50)))
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID adminId) {
        return notificationRepository.countByRecipientIdAndReadAtIsNull(adminId);
    }

    @Transactional
    public AdminNotificationResponse markRead(UUID adminId, UUID notificationId) {
        AdminNotification notification = notificationRepository.findById(notificationId)
                .filter(item -> item.getRecipient().getId().equals(adminId))
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (notification.getReadAt() == null) notification.setReadAt(LocalDateTime.now());
        return toResponse(notificationRepository.save(notification));
    }

    @Transactional
    public void markAllRead(UUID adminId) {
        notificationRepository.findByRecipientIdOrderByCreatedAtDesc(adminId, PageRequest.of(0, 50))
                .forEach(notification -> {
                    if (notification.getReadAt() == null) notification.setReadAt(LocalDateTime.now());
                });
        notificationRepository.flush();
    }

    private AdminNotificationResponse toResponse(AdminNotification notification) {
        return AdminNotificationResponse.builder()
                .id(notification.getId())
                .type(notification.getType())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .read(notification.getReadAt() != null)
                .createdAt(notification.getCreatedAt())
                .build();
    }
}
