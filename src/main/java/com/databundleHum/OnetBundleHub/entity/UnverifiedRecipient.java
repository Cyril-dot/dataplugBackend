package com.databundleHum.OnetBundleHub.entity;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

@Entity
@Table(name = "unverified_recipients", uniqueConstraints = @UniqueConstraint(
        name = "uq_unverified_recipient_network", columnNames = {"phone_number", "network"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UnverifiedRecipient {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlatformSettings.Network network;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "source_email", length = 200)
    private String sourceEmail;

    @Column(name = "source_full_name", length = 200)
    private String sourceFullName;

    @Column(nullable = false)
    @Builder.Default
    private int attempts = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReviewStatus status = ReviewStatus.UNVERIFIED;

    @Column(name = "first_failed_at", nullable = false)
    @Builder.Default
    private LocalDateTime firstFailedAt = LocalDateTime.now();

    @Column(name = "last_failed_at", nullable = false)
    @Builder.Default
    private LocalDateTime lastFailedAt = LocalDateTime.now();

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verified_by", length = 200)
    private String verifiedBy;

    @Column(name = "last_notified_at")
    private LocalDateTime lastNotifiedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by_user_id")
    @JsonIgnore
    private User submittedBy;

    @Column(name = "submission_source", length = 30)
    @Builder.Default
    private String submissionSource = "ORDER_FAILURE";

    public enum ReviewStatus { UNVERIFIED, SUBMITTED, VERIFIED }
}
