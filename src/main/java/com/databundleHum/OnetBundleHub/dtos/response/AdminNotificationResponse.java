package com.databundleHum.OnetBundleHub.dtos.response;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminNotificationResponse {
    private UUID id;
    private String type;
    private String title;
    private String message;
    private boolean read;
    private LocalDateTime createdAt;
}
