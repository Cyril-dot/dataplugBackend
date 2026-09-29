package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.entity.PlatformSettings;
import com.databundleHum.OnetBundleHub.entity.UnverifiedRecipient;
import com.databundleHum.OnetBundleHub.dtos.response.RecipientVerificationResponse;
import com.databundleHum.OnetBundleHub.repos.UnverifiedRecipientRepository;
import com.databundleHum.OnetBundleHub.repos.UserRepository;
import com.databundleHum.OnetBundleHub.security.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UnverifiedRecipientService {
    private final UnverifiedRecipientRepository repository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /** Manual-review state; a missing row is NOT_REPORTED, never VERIFIED. */
    @Transactional(readOnly = true)
    public RecipientVerificationResponse check(String phone, PlatformSettings.Network network) {
        String normalizedPhone = normalizePhone(phone);
        if (network != PlatformSettings.Network.MTN) {
            return RecipientVerificationResponse.builder().phoneNumber(normalizedPhone)
                    .network(network.name()).status("NOT_APPLICABLE").canPlaceOrder(true)
                    .message("MTN verification is currently required for MTN data delivery only.")
                    .build();
        }
        UnverifiedRecipient item = repository.findByPhoneNumberAndNetwork(normalizedPhone, network).orElse(null);
        if (item == null) {
            return RecipientVerificationResponse.builder().phoneNumber(normalizedPhone)
                    .network(network.name()).status("NOT_REPORTED").canPlaceOrder(true)
                    .message("This number has not been submitted for MTN verification and is available for data delivery.")
                    .build();
        }
        boolean canPlace = item.getStatus() == UnverifiedRecipient.ReviewStatus.VERIFIED;
        return RecipientVerificationResponse.builder().phoneNumber(normalizedPhone)
                .network(network.name()).status(item.getStatus().name()).canPlaceOrder(canPlace)
                .message(canPlace ? "This number is verified for MTN data delivery."
                        : "This number has been submitted for MTN verification and cannot receive an order yet.")
                .attempts(item.getAttempts()).failureReason(item.getFailureReason())
                .lastFailedAt(item.getLastFailedAt()).verifiedAt(item.getVerifiedAt())
                .verifiedBy(item.getVerifiedBy()).build();
    }

    /** Blocks only numbers explicitly awaiting manual admin review. */
    public void assertOrderAllowed(String phone, PlatformSettings.Network network) {
        RecipientVerificationResponse result = check(phone, network);
        if (!result.isCanPlaceOrder() && ("UNVERIFIED".equals(result.getStatus())
                || "SUBMITTED".equals(result.getStatus()))) {
            throw new ValidationException("This number has been submitted for MTN verification. Please wait for verification or use another number.");
        }
    }

    @Transactional
    public void recordFailure(String phone, PlatformSettings.Network network, String reason,
                              String sourceEmail, String sourceFullName) {
        UnverifiedRecipient item = repository.findByPhoneNumberAndNetwork(normalizePhone(phone), network)
                .orElseGet(() -> UnverifiedRecipient.builder()
                        .phoneNumber(normalizePhone(phone)).network(network).firstFailedAt(LocalDateTime.now()).build());
        item.setFailureReason(trim(reason));
        item.setSourceEmail(sourceEmail);
        item.setSourceFullName(sourceFullName);
        item.setLastFailedAt(LocalDateTime.now());
        item.setAttempts(item.getAttempts() <= 0 ? 1 : item.getAttempts() + 1);
        if (item.getStatus() == null || item.getStatus() == UnverifiedRecipient.ReviewStatus.VERIFIED) {
            item.setStatus(UnverifiedRecipient.ReviewStatus.UNVERIFIED);
            item.setVerifiedAt(null);
            item.setVerifiedBy(null);
        }
        repository.save(item);
    }

    public List<UnverifiedRecipient> daily(LocalDate date) {
        ZoneId zone = ZoneOffset.UTC;
        LocalDateTime from = date.atStartOfDay(zone).toLocalDateTime();
        LocalDateTime to = date.plusDays(1).atStartOfDay(zone).toLocalDateTime();
        return repository.findByLastFailedAtBetweenOrderByLastFailedAtDesc(from, to);
    }

    public byte[] csv(LocalDate date) {
        StringBuilder out = new StringBuilder("id,phone_number,network,attempts,status,source_email,source_full_name,failure_reason,first_failed_at,last_failed_at\n");
        for (UnverifiedRecipient item : daily(date)) {
            out.append(item.getId()).append(',').append(csv(item.getPhoneNumber())).append(',')
                    .append(item.getNetwork()).append(',').append(item.getAttempts()).append(',')
                    .append(item.getStatus()).append(',').append(csv(item.getSourceEmail())).append(',')
                    .append(csv(item.getSourceFullName())).append(',').append(csv(item.getFailureReason())).append(',')
                    .append(item.getFirstFailedAt()).append(',').append(item.getLastFailedAt()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public UnverifiedRecipient submit(Long id) {
        UnverifiedRecipient item = repository.findById(id).orElseThrow();
        if (item.getStatus() == UnverifiedRecipient.ReviewStatus.UNVERIFIED) item.setStatus(UnverifiedRecipient.ReviewStatus.SUBMITTED);
        return repository.save(item);
    }

    @Transactional
    public UnverifiedRecipient markVerified(Long id, String adminEmail) {
        UnverifiedRecipient item = repository.findById(id).orElseThrow();
        item.setStatus(UnverifiedRecipient.ReviewStatus.VERIFIED);
        item.setVerifiedAt(LocalDateTime.now());
        item.setVerifiedBy(adminEmail);
        UnverifiedRecipient saved = repository.save(item);
        userRepository.findAllByRole(com.databundleHum.OnetBundleHub.entity.User.Role.SUPER_ADMIN)
                .forEach(admin -> notificationService.sendUnverifiedRecipientVerifiedAlert(
                        admin.getEmail(), admin.getFullName(), saved.getPhoneNumber()));
        return saved;
    }

    public void notifySourceAccount(Long id) {
        UnverifiedRecipient item = repository.findById(id).orElseThrow();
        if (item.getSourceEmail() == null || item.getSourceEmail().isBlank()) return;
        notificationService.sendFailedRecipientVerifiedAlert(item.getSourceEmail(),
                item.getSourceFullName(), item.getPhoneNumber());
        item.setLastNotifiedAt(LocalDateTime.now());
        repository.save(item);
    }

    private String trim(String value) { return value == null ? "Unknown provider rejection" : value.substring(0, Math.min(500, value.length())); }
    private String csv(String value) { if (value == null) return ""; return "\"" + value.replace("\"", "\"\"") + "\""; }
    private String normalizePhone(String value) { return value == null ? "" : value.replaceAll("\\D", ""); }
}
