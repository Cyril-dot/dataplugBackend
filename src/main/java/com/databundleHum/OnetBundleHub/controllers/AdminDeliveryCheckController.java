package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.dtos.response.PhoneDeliveryCheckResponse;
import com.databundleHum.OnetBundleHub.entity.Order;
import com.databundleHum.OnetBundleHub.repos.OrderRepository;
import com.databundleHum.OnetBundleHub.security.ResourceNotFoundException;
import com.databundleHum.OnetBundleHub.services.DataBossHubService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Manual, human-facing delivery corroboration for a specific order.
 *
 * NOT an automated status source — see DataBossHubService's Javadoc for why
 * a phone-level checker can't safely auto-confirm a specific orderId. This
 * endpoint is for an admin/support agent investigating a stuck or disputed
 * order to get a second data point, alongside the order's own DataPrimo
 * status (already visible via the order's normal status field).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")  // ✅ FIXED: was hasRole('ADMIN') — role is ROLE_SUPER_ADMIN
public class AdminDeliveryCheckController {

    private final OrderRepository orderRepository;
    private final DataBossHubService dataBossHubService;

    /**
     * GET /api/admin/orders/{orderId}/delivery-check
     *
     * Looks up the order's own recorded status alongside a fresh
     * DataBossHub phone-delivery corroboration check for the order's
     * recipient number. Both are returned side by side — the caller
     * (admin dashboard) decides what to do with the combination; this
     * endpoint never mutates the order itself.
     */
    @GetMapping("/{orderId}/delivery-check")
    public ResponseEntity<DeliveryCheckResult> checkDelivery(@PathVariable Long orderId) {
        log.info("[ADMIN-DELIVERY-CHECK] Requested for orderId={}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        PhoneDeliveryCheckResponse corroboration =
                dataBossHubService.checkPhoneDelivery(order.getPhoneNumber());

        DeliveryCheckResult result = DeliveryCheckResult.builder()
                .orderId(order.getId())
                .phoneNumber(order.getPhoneNumber())
                .orderStatus(order.getStatus().name())
                .dataprimoOrderId(order.getDataprimoOrderId())
                .corroboration(corroboration)
                .build();

        log.info("[ADMIN-DELIVERY-CHECK] orderId={} orderStatus={} corroborationStatus={}",
                orderId, order.getStatus(), corroboration.getStatus());

        return ResponseEntity.ok(result);
    }

    /**
     * Combined view returned to the admin dashboard — the order's own
     * authoritative status plus the supplementary phone-level signal.
     */
    @lombok.Data
    @lombok.Builder
    public static class DeliveryCheckResult {
        private Long orderId;
        private String phoneNumber;
        /** The order's own status, as set by DataPrimoService's poller — authoritative. */
        private String orderStatus;
        private String dataprimoOrderId;
        /** Supplementary, phone-level, non-authoritative — see class Javadoc. */
        private PhoneDeliveryCheckResponse corroboration;
    }
}
