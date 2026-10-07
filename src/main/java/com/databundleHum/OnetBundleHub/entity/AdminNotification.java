package com.databundleHum.OnetBundleHub.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "admin_notifications", indexes = {
        @Index(name = "idx_admin_notification_recipient_created", columnList = "recipient_id,created_at"),
        @Index(name = "idx_admin_notification_recipient_read", columnList = "recipient_id,read_at")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_admin_notification_recipient_event", columnNames = {"recipient_id", "event_key"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminNotification {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false, columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Column(name = "event_key", nullable = false, length = 180)
    private String eventKey;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
