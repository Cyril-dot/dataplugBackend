package com.databundleHum.OnetBundleHub.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Asynchronous notification service — email via SendGrid, SMS via Arkesel.
 *
 * <p>All methods are {@code @Async} so they never block the main request thread.
 * A failure here is caught and logged but never propagates to the caller.
 *
 * <p>Environment variables required:
 * <ul>
 *   <li>{@code SENDGRID_API_KEY} — SendGrid API key (sk_...)</li>
 *   <li>{@code ARKESEL_API_KEY} — Arkesel SMS key</li>
 *   <li>{@code APP_FROM_EMAIL} — e.g. noreply@databundleshub.com</li>
 *   <li>{@code APP_FROM_NAME} — e.g. DataBundlesHub</li>
 * </ul>
 *
 * ── CHECKER FEATURE (2026-08-26) ──────────────────────────────────────────
 * sendCheckerCredentialsSms() is a new PUBLIC method added for
 * CheckerService — result-checker orders deliver serial/PIN via SMS in
 * addition to showing them on-screen in the API response. It reuses the
 * existing private sendSms() helper below (unchanged).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    @Value("${sendgrid.api-key:}")
    private String sendgridApiKey;

    @Value("${arkesel.api-key:}")
    private String arkeselApiKey;

    @Value("${app.from-email:noreply@databundleshub.com}")
    private String fromEmail;

    @Value("${app.from-name:DataBundlesHub}")
    private String fromName;

    // ── Welcome email ─────────────────────────────────────────────────────────

    /** Sends a welcome email to a newly registered user. */
    @Async
    public void sendWelcomeEmail(String email, String fullName) {
        log.info("Sending welcome email: to={}", email);
        sendEmail(email, fullName, "Welcome to DataBundlesHub!",
                "Hi " + fullName + ",\n\nWelcome to DataBundlesHub. "
                        + "Top up your wallet and start buying data bundles instantly.\n\nTeam DBH");
    }

    // ── Password alerts ───────────────────────────────────────────────────────

    /** Notifies the user that their password was changed. */
    @Async
    public void sendPasswordChangedAlert(String email, String fullName) {
        log.info("Sending password changed alert: to={}", email);
        sendEmail(email, fullName, "Your DataBundlesHub password was changed",
                "Hi " + fullName + ",\n\nYour account password was just changed. "
                        + "If this wasn't you, please contact support immediately.\n\nTeam DBH");
    }

    // ── Order alerts ──────────────────────────────────────────────────────────

    /**
     * Notifies the user that their bundle order could not be fulfilled.
     *
     * @param email   recipient email address
     * @param fullName recipient display name
     * @param orderId  the failed order ID
     */
    @Async
    public void sendOrderFailedAlert(String email, String fullName, Long orderId) {
        log.warn("Sending order failed alert: to={} orderId={}", email, orderId);
        sendEmail(email, fullName, "Your data bundle order failed — Order #" + orderId,
                "Hi " + fullName + ",\n\nUnfortunately your data bundle order #" + orderId
                        + " could not be fulfilled. Our support team will review and issue a refund "
                        + "if payment was taken.\n\nTeam DBH");
    }

    // ── Checker alerts (NEW) ────────────────────────────────────────────────────

    /**
     * Delivers a purchased result-checker's serial/PIN via SMS.
     * Called by CheckerService immediately after a DataBossHub checker
     * purchase succeeds. Never throws — SMS failures are logged only, same
     * as every other notification method here; the credentials are also
     * always returned in the API response and stored in checker history,
     * so SMS delivery failing never means the customer loses their PIN.
     *
     * @param phone       recipient phone number
     * @param examType    "BECE" or "WASSCE" (display string)
     * @param serial      checker serial number
     * @param pin         checker PIN
     * @param resultsLink optional URL to check results, may be null
     */
    @Async
    /**
     * ✅ Made @Async — SMS delivery is a nice-to-have notification, not the
     * actual deliverable. The checker's serial/PIN are already returned
     * directly in the purchase API response and viewable anytime after via
     * checker history, so a slow or failing SMS provider call should never
     * hold up (or, worse, appear to fail) a purchase that has already
     * genuinely succeeded — the wallet's already been debited and the
     * checker's already been assigned by the time this runs.
     */
    @Async
    public void sendCheckerCredentialsSms(String phone, String examType, String serial,
                                          String pin, String resultsLink) {
        log.info("Sending checker credentials SMS: to={} examType={}", phone, examType);

        StringBuilder message = new StringBuilder();
        message.append("Your ").append(examType).append(" checker:\n");
        message.append("Serial: ").append(serial).append("\n");
        message.append("PIN: ").append(pin);
        if (resultsLink != null && !resultsLink.isBlank()) {
            message.append("\nCheck results: ").append(resultsLink);
        }

        sendSms(phone, message.toString());
    }

    /**
     * Notifies the user that their checker order could not be fulfilled.
     * Mirrors sendOrderFailedAlert but for checker purchases.
     */
    @Async
    public void sendCheckerOrderFailedAlert(String email, String fullName, Long checkerOrderId) {
        log.warn("Sending checker order failed alert: to={} checkerOrderId={}", email, checkerOrderId);
        sendEmail(email, fullName, "Your result checker order failed — Order #" + checkerOrderId,
                "Hi " + fullName + ",\n\nUnfortunately your result checker order #" + checkerOrderId
                        + " could not be fulfilled. Our support team will review and issue a refund "
                        + "if payment was taken.\n\nTeam DBH");
    }

    // ── Reseller alerts ───────────────────────────────────────────────────────

    /** Notifies the reseller that their application was approved. */
    @Async
    public void sendResellerApprovedEmail(String email, String fullName) {
        log.info("Sending reseller approved email: to={}", email);
        sendEmail(email, fullName, "Congratulations — Reseller Application Approved!",
                "Hi " + fullName + ",\n\nYour reseller application has been approved! "
                        + "Log in to your dashboard at /reseller/dashboard to set your selling prices "
                        + "and start earning.\n\nTeam DBH");
    }

    /**
     * Notifies the reseller that their application was rejected and the fee refunded.
     *
     * @param email    recipient email address
     * @param fullName recipient display name
     * @param reason   optional rejection reason shown to the applicant, may be {@code null}
     */
    @Async
    public void sendResellerRejectedEmail(String email, String fullName, String reason) {
        log.info("Sending reseller rejected email: to={}", email);
        sendEmail(email, fullName, "Reseller Application Update",
                "Hi " + fullName + ",\n\nUnfortunately your reseller application was not approved at this time."
                        + (reason != null ? "\n\nReason: " + reason : "")
                        + "\n\nYour GHS 20 registration fee has been refunded to your wallet.\n\nTeam DBH");
    }

    // ── Payout alerts ─────────────────────────────────────────────────────────

    /**
     * Notifies the reseller that their payout has been sent.
     *
     * @param email    recipient email address
     * @param fullName recipient display name
     * @param amount   payout amount in GHS
     */
    @Async
    public void sendPayoutPaidAlert(String email, String fullName, BigDecimal amount) {
        log.info("Sending payout paid alert: to={} amount={}", email, amount);
        sendEmail(email, fullName, "Payout Sent — GHS " + amount,
                "Hi " + fullName + ",\n\nYour payout of GHS " + amount
                        + " has been sent to your Mobile Money number. "
                        + "Please allow a few minutes for it to arrive.\n\nTeam DBH");
    }

    /**
     * Notifies the reseller that their payout request was rejected.
     *
     * @param email    recipient email address
     * @param fullName recipient display name
     * @param amount   the rejected payout amount in GHS
     * @param reason   optional rejection reason, may be {@code null}
     */
    @Async
    public void sendPayoutRejectedAlert(String email, String fullName,
                                        BigDecimal amount, String reason) {
        log.warn("Sending payout rejected alert: to={} amount={}", email, amount);
        sendEmail(email, fullName, "Payout Request Rejected — GHS " + amount,
                "Hi " + fullName + ",\n\nYour payout request of GHS " + amount + " was rejected."
                        + (reason != null ? "\n\nReason: " + reason : "")
                        + "\n\nPlease contact support if you have questions.\n\nTeam DBH");
    }

    // ── Core send helpers ─────────────────────────────────────────────────────

    /**
     * Sends a plain-text email via the SendGrid REST API.
     * Replace the body structure with HTML and SendGrid dynamic templates in production.
     *
     * @param toEmail  recipient address
     * @param toName   recipient display name
     * @param subject  email subject line
     * @param body     plain-text body content
     */
    private void sendEmail(String toEmail, String toName, String subject, String body) {
        if (sendgridApiKey == null || sendgridApiKey.isBlank()) {
            log.warn("SendGrid API key not configured — skipping email to {}", toEmail);
            return;
        }

        try {
            Map<String, Object> payload = Map.of(
                    "personalizations", new Object[]{
                            Map.of("to", new Object[]{Map.of("email", toEmail, "name", toName)})
                    },
                    "from",    Map.of("email", fromEmail, "name", fromName),
                    "subject", subject,
                    "content", new Object[]{Map.of("type", "text/plain", "value", body)}
            );

            WebClient.create("https://api.sendgrid.com").post()
                    .uri("/v3/mail/send")
                    .header("Authorization", "Bearer " + sendgridApiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();

            log.debug("Email sent successfully: to={} subject={}", toEmail, subject);

        } catch (Exception ex) {
            // Never propagate notification failures to the calling transaction
            log.error("Failed to send email to {}: {}", toEmail, ex.getMessage());
        }
    }

    /**
     * Sends an SMS via the Arkesel API.
     * Used for OTP, order status updates, and checker credential delivery.
     *
     * @param phone   destination phone number
     * @param message SMS body text
     */
    private void sendSms(String phone, String message) {
        if (arkeselApiKey == null || arkeselApiKey.isBlank()) {
            log.warn("Arkesel API key not configured — skipping SMS to {}", phone);
            return;
        }

        try {
            WebClient.create("https://sms.arkesel.com").get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/sms/api")
                            .queryParam("action", "send-sms")
                            .queryParam("api_key", arkeselApiKey)
                            .queryParam("to",      phone)
                            .queryParam("from",    fromName)
                            .queryParam("sms",     message)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("SMS sent: to={}", phone);

        } catch (Exception ex) {
            log.error("Failed to send SMS to {}: {}", phone, ex.getMessage());
        }
    }
}
