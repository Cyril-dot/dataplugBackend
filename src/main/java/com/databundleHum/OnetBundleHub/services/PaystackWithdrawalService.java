package com.databundleHum.OnetBundleHub.services;

import com.databundleHum.OnetBundleHub.config.PaystackConfig;
import com.databundleHum.OnetBundleHub.dtos.AdminPaystackWithdrawalRequest;
import com.databundleHum.OnetBundleHub.dtos.response.AdminPaystackWithdrawalResponse;
import com.databundleHum.OnetBundleHub.entity.PaystackWithdrawal;
import com.databundleHum.OnetBundleHub.entity.User;
import com.databundleHum.OnetBundleHub.repos.PaystackWithdrawalRepository;
import com.databundleHum.OnetBundleHub.repos.UserRepository;
import com.databundleHum.OnetBundleHub.security.ResourceNotFoundException;
import com.databundleHum.OnetBundleHub.security.UpstreamApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaystackWithdrawalService {
    private final PaystackWithdrawalRepository withdrawalRepository;
    private final UserRepository userRepository;
    private final WebClient paystackWebClient;
    private final PaystackConfig paystackConfig;

    @Transactional(readOnly = true)
    public Map<String, Object> getBalance() {
        Map<String, Object> response = get("/balance");
        Object data = response.get("data");
        if (data instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object currency = first.get("currency");
            Object rawBalance = first.get("balance");
            String currencyValue = currency == null ? "GHS" : String.valueOf(currency);
            String balanceValue = rawBalance == null ? "0" : String.valueOf(rawBalance);
            BigDecimal balanceGhc = new BigDecimal(balanceValue)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            return Map.of("currency", currencyValue, "balancePesewas", rawBalance == null ? 0 : rawBalance,
                    "balanceGhc", balanceGhc);
        }
        return Map.of("currency", "GHS", "balancePesewas", 0, "balanceGhc", BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public Page<AdminPaystackWithdrawalResponse> list(PaystackWithdrawal.WithdrawalStatus status, Pageable pageable) {
        Page<PaystackWithdrawal> page = status == null
                ? withdrawalRepository.findAllByOrderByCreatedAtDesc(pageable)
                : withdrawalRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        return page.map(this::toResponse);
    }

    public AdminPaystackWithdrawalResponse request(UUID adminId, AdminPaystackWithdrawalRequest request) {
        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin account not found"));
        BigDecimal amount = request.getAmountGhc().setScale(2, RoundingMode.HALF_UP);
        long pesewas = amount.multiply(BigDecimal.valueOf(100)).longValueExact();
        // Paystack references must be lowercase alphanumeric, dash or underscore.
        String reference = "dp-wd-" + UUID.randomUUID().toString().replace("-", "");

        PaystackWithdrawal withdrawal = withdrawalRepository.save(PaystackWithdrawal.builder()
                .reference(reference).amountGhc(amount).amountPesewas(pesewas)
                .payoutType(request.getPayoutType()).accountName(request.getAccountName().trim())
                .accountNumber(request.getAccountNumber().trim()).bankCode(request.getBankCode().trim())
                .reason(request.getReason() == null ? "Platform withdrawal" : request.getReason().trim())
                .requestedBy(admin).status(PaystackWithdrawal.WithdrawalStatus.PENDING).build());

        try {
            Map<String, Object> balance = getBalance();
            BigDecimal available = (BigDecimal) balance.get("balanceGhc");
            if (!"GHS".equalsIgnoreCase(String.valueOf(balance.get("currency")))
                    || available.compareTo(amount) < 0) {
                String message = String.format(Locale.ROOT,
                        "Insufficient Paystack GHS balance. Available: GHS %.2f; requested: GHS %.2f. Top up the Paystack Balance before trying again.",
                        available, amount);
                withdrawal.setStatus(PaystackWithdrawal.WithdrawalStatus.FAILED);
                withdrawal.setFailureReason(message);
                withdrawalRepository.save(withdrawal);
                return toResponse(withdrawal);
            }
            Map<String, Object> recipient = createRecipient(withdrawal);
            String recipientCode = String.valueOf(((Map<?, ?>) recipient.get("data")).get("recipient_code"));
            Map<String, Object> transfer = initiateTransfer(withdrawal, recipientCode);
            Map<?, ?> data = (Map<?, ?>) transfer.get("data");
            withdrawal.setRecipientCode(recipientCode);
            withdrawal.setTransferCode(data == null ? null : String.valueOf(data.get("transfer_code")));
            withdrawal.setStatus(mapStatus(data == null ? null : String.valueOf(data.get("status"))));
            withdrawal.setFailureReason(null);
            withdrawalRepository.save(withdrawal);
        } catch (UpstreamApiException ex) {
            // A well-formed Paystack rejection is conclusive. It is safe to mark
            // failed; only transport/5xx failures remain uncertain and pending.
            withdrawal.setStatus(PaystackWithdrawal.WithdrawalStatus.FAILED);
            withdrawal.setFailureReason(ex.getMessage());
            withdrawalRepository.save(withdrawal);
        } catch (WebClientResponseException ex) {
            // Do not retry automatically: a timeout/5xx may mean Paystack accepted it.
            withdrawal.setStatus(ex.getStatusCode().is4xxClientError()
                    ? PaystackWithdrawal.WithdrawalStatus.FAILED
                    : PaystackWithdrawal.WithdrawalStatus.PENDING);
            withdrawal.setFailureReason(ex.getStatusCode().is4xxClientError()
                    ? "Paystack rejected the transfer: " + safeMessage(ex)
                    : "Transfer outcome could not be confirmed; verify this reference before retrying.");
            withdrawalRepository.save(withdrawal);
            if (ex.getStatusCode().is5xxServerError()) {
                log.error("[PAYSTACK-TRANSFER] Ambiguous response ref={}; verify before retrying", reference, ex);
            }
        } catch (RuntimeException ex) {
            withdrawal.setFailureReason("Transfer outcome could not be confirmed; verify this reference before retrying.");
            withdrawalRepository.save(withdrawal);
            log.error("[PAYSTACK-TRANSFER] Ambiguous failure ref={}", reference, ex);
        }
        return toResponse(withdrawal);
    }

    @Transactional
    public AdminPaystackWithdrawalResponse verify(Long id) {
        PaystackWithdrawal withdrawal = withdrawalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Withdrawal not found"));
        Map<String, Object> response = get("/transfer/verify/" + withdrawal.getReference());
        Map<?, ?> data = response.get("data") instanceof Map<?, ?> map ? map : Map.of();
        applyStatus(withdrawal, String.valueOf(data.get("status")),
                data.get("transfer_code") == null ? null : String.valueOf(data.get("transfer_code")), "manual verification");
        return toResponse(withdrawalRepository.save(withdrawal));
    }

    @Transactional
    public AdminPaystackWithdrawalResponse finalizeOtp(Long id, String otp) {
        PaystackWithdrawal withdrawal = withdrawalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Withdrawal not found"));
        if (withdrawal.getTransferCode() == null || withdrawal.getTransferCode().isBlank()) {
            throw new UpstreamApiException("Paystack has not returned a transfer code for this withdrawal");
        }
        Map<String, Object> body = Map.of("transfer_code", withdrawal.getTransferCode(), "otp", otp.trim());
        Map<String, Object> response = post("/transfer/finalize_transfer", body);
        Map<?, ?> data = response.get("data") instanceof Map<?, ?> map ? map : Map.of();
        applyStatus(withdrawal, String.valueOf(data.get("status")), withdrawal.getTransferCode(), "OTP finalized");
        return toResponse(withdrawalRepository.save(withdrawal));
    }

    @Transactional
    public void handleTransferEvent(String event, String reference, String reason) {
        withdrawalRepository.findByReference(reference).ifPresentOrElse(withdrawal -> {
            String status = switch (event) {
                case "transfer.success" -> "success";
                case "transfer.failed" -> "failed";
                case "transfer.reversed" -> "reversed";
                default -> null;
            };
            if (status != null) applyStatus(withdrawal, status, null, reason);
            withdrawalRepository.save(withdrawal);
        }, () -> log.warn("[PAYSTACK-TRANSFER] Unknown webhook reference={}", reference));
    }

    @Scheduled(fixedDelay = 300_000L)
    @Transactional
    public void reconcile() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);
        withdrawalRepository.findByStatusInAndUpdatedAtBefore(
                List.of(PaystackWithdrawal.WithdrawalStatus.PENDING, PaystackWithdrawal.WithdrawalStatus.PROCESSING), cutoff)
                .forEach(w -> {
                    try {
                        Map<String, Object> response = get("/transfer/verify/" + w.getReference());
                        Map<?, ?> data = response.get("data") instanceof Map<?, ?> map ? map : Map.of();
                        applyStatus(w, String.valueOf(data.get("status")), null, "scheduled reconciliation");
                        withdrawalRepository.save(w);
                    } catch (WebClientResponseException ex) {
                        log.warn("[PAYSTACK-TRANSFER] Reconciliation failed ref={} status={}", w.getReference(), ex.getStatusCode());
                    }
                });
    }

    private Map<String, Object> createRecipient(PaystackWithdrawal w) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", w.getPayoutType() == PaystackWithdrawal.PayoutType.MOBILE_MONEY ? "mobile_money" : "ghipss");
        body.put("name", w.getAccountName()); body.put("account_number", w.getAccountNumber());
        body.put("bank_code", w.getBankCode().toUpperCase(Locale.ROOT)); body.put("currency", "GHS");
        return post("/transferrecipient", body);
    }

    private Map<String, Object> initiateTransfer(PaystackWithdrawal w, String recipientCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "balance"); body.put("amount", w.getAmountPesewas()); body.put("recipient", recipientCode);
        body.put("reason", w.getReason()); body.put("reference", w.getReference()); body.put("currency", "GHS");
        return post("/transfer", body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) {
        Map<String, Object> response = paystackWebClient.post().uri(path).bodyValue(body).retrieve()
                .bodyToMono(Map.class).block();
        if (response == null || !Boolean.TRUE.equals(response.get("status"))) {
            throw new UpstreamApiException("Paystack rejected the transfer: " + formatProviderError(response));
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        Map<String, Object> response = paystackWebClient.get().uri(path).retrieve().bodyToMono(Map.class).block();
        if (response == null || !Boolean.TRUE.equals(response.get("status"))) {
            throw new UpstreamApiException("Paystack transfer lookup failed");
        }
        return response;
    }

    private void applyStatus(PaystackWithdrawal w, String providerStatus, String transferCode, String reason) {
        if (transferCode != null && !"null".equals(transferCode)) w.setTransferCode(transferCode);
        PaystackWithdrawal.WithdrawalStatus next = mapStatus(providerStatus);
        if (next == PaystackWithdrawal.WithdrawalStatus.FAILED || next == PaystackWithdrawal.WithdrawalStatus.REVERSED) w.setFailureReason(reason);
        w.setStatus(next);
    }

    private PaystackWithdrawal.WithdrawalStatus mapStatus(String status) {
        return switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
            case "success" -> PaystackWithdrawal.WithdrawalStatus.SUCCESS;
            case "failed" -> PaystackWithdrawal.WithdrawalStatus.FAILED;
            case "reversed" -> PaystackWithdrawal.WithdrawalStatus.REVERSED;
            default -> PaystackWithdrawal.WithdrawalStatus.PROCESSING;
        };
    }

    private String safeMessage(WebClientResponseException ex) {
        String message = ex.getResponseBodyAsString();
        return message == null || message.isBlank() ? ex.getStatusText() : message.substring(0, Math.min(500, message.length()));
    }

    private String formatProviderError(Map<String, Object> response) {
        if (response == null) return "empty response";
        String message = String.valueOf(response.getOrDefault("message", "unknown error"));
        Object meta = response.get("meta");
        if (meta instanceof Map<?, ?> metaMap && metaMap.get("nextStep") != null) {
            message += " (" + metaMap.get("nextStep") + ")";
        }
        Object code = response.get("code");
        if (code != null) message += " [" + code + "]";
        return message;
    }

    private AdminPaystackWithdrawalResponse toResponse(PaystackWithdrawal w) {
        String account = w.getAccountNumber();
        String masked = account.length() <= 4 ? account : "••••" + account.substring(account.length() - 4);
        return AdminPaystackWithdrawalResponse.builder().id(w.getId()).reference(w.getReference())
                .amountGhc(w.getAmountGhc()).payoutType(w.getPayoutType().name()).accountName(w.getAccountName())
                .maskedAccountNumber(masked).bankCode(w.getBankCode()).reason(w.getReason()).status(w.getStatus().name())
                .transferCode(w.getTransferCode()).failureReason(w.getFailureReason())
                .requestedBy(w.getRequestedBy() == null ? null : w.getRequestedBy().getFullName())
                .createdAt(w.getCreatedAt()).updatedAt(w.getUpdatedAt()).build();
    }
}
