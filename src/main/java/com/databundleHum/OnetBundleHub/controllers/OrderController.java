package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.dtos.*;
import com.databundleHum.OnetBundleHub.dtos.response.*;
import com.databundleHum.OnetBundleHub.security.UserPrincipal;
import com.databundleHum.OnetBundleHub.services.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Bundle purchase, top-up, and order management")
public class OrderController {

    private final OrderService orderService;

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        return principal.userId();
    }

    // ── Guest checkout ────────────────────────────────────────────────────────

    @PostMapping("/guest")
    @Operation(summary = "Initiate a guest bundle order via Paystack")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order initiated — use paystackReference to complete payment"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "404", description = "Bundle not available")
    })
    public ResponseEntity<InitiateOrderResponse> initiateGuestOrder(
            @Valid @RequestBody InitiateGuestOrderRequest request) {

        log.info("[ORDER] Guest order initiated: phone={} network={} gb={}",
                request.getPhoneNumber(), request.getNetwork(), request.getCapacityGb());
        InitiateOrderResponse response = orderService.initiateGuestOrder(request);
        log.info("[ORDER] Guest order created: ref={}", response.getPaystackReference());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/status")
    @Operation(summary = "Poll guest order status by Paystack reference")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order status returned"),
            @ApiResponse(responseCode = "404", description = "No order found for that reference")
    })
    public ResponseEntity<OrderResponse> getOrderStatusByRef(
            @RequestParam String ref) {

        log.debug("[ORDER] Status check for ref={}", ref);
        return ResponseEntity.ok(orderService.getOrderStatusByRef(ref));
    }

    // ── Wallet top-up ─────────────────────────────────────────────────────────

    @PostMapping("/topup/initiate")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Initiate a wallet top-up via Paystack")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Top-up initiated"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<TopUpInitiateResponse> initiateTopUp(
            @Valid @RequestBody TopUpInitiateRequest request) {

        UUID userId = currentUserId();
        log.info("[ORDER] Wallet top-up initiated: userId={} amount={}", userId, request.getAmount());
        TopUpInitiateResponse response = orderService.initiateTopUp(userId, request);
        log.info("[ORDER] Top-up ref generated: userId={} ref={}", userId, response.getPaystackReference());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/topup/verify")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Manually verify a top-up if the webhook was missed")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Top-up credited or already processed"),
            @ApiResponse(responseCode = "400", description = "Paystack verification failed")
    })
    public ResponseEntity<WalletResponse> verifyTopUp(
            @Valid @RequestBody TopUpVerifyRequest request) {

        UUID userId = currentUserId();
        log.info("[ORDER] Manual top-up verify: userId={} ref={}", userId, request.getPaystackRef());
        WalletResponse response = orderService.verifyTopUp(userId, request);
        log.info("[ORDER] Top-up verify complete: userId={} balance={}", userId, response.getBalance());
        return ResponseEntity.ok(response);
    }

    // ── Authenticated wallet order ─────────────────────────────────────────────

    @PostMapping("/wallet")
    @PreAuthorize("hasAnyRole('USER', 'RESELLER', 'SUPER_ADMIN')")  // ← FIXED
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Place a bundle order using wallet balance (public price)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order placed and provisioning started"),
            @ApiResponse(responseCode = "400", description = "Insufficient wallet balance or validation error"),
            @ApiResponse(responseCode = "404", description = "Bundle not available")
    })
    public ResponseEntity<OrderResponse> placeWalletOrder(
            @Valid @RequestBody WalletOrderRequest request) {

        UUID userId = currentUserId();
        log.info("[ORDER] Wallet order: userId={} phone={} network={} gb={}",
                userId, request.getPhoneNumber(), request.getNetwork(), request.getCapacityGb());
        OrderResponse response = orderService.placeWalletOrder(userId, request);
        log.info("[ORDER] Wallet order placed: userId={} orderId={} status={}",
                userId, response.getId(), response.getStatus());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ── Reseller wallet order ─────────────────────────────────────────────────

    // ── Reseller wallet order ─────────────────────────────────────────────────

    /**
     * FIX (2026-07-23): sellingPrice arrives here as a raw query parameter
     * from the reseller's own client — nothing stopped it being omitted,
     * zero, or negative before reaching the service layer. jakarta.validation
     * annotations can't be applied directly to a @RequestParam the way they
     * can to a @RequestBody DTO field, so we do an explicit guard here as a
     * first line of defense. The authoritative check — sellingPrice must not
     * be below the platform's cost price for this bundle — lives in
     * OrderService.placeResellerWalletOrder() and cannot be bypassed even if
     * this controller-level guard were ever removed.
     */
    @PostMapping("/reseller/wallet")
    @PreAuthorize("hasRole('RESELLER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Place a bundle order at reseller (wholesale) price")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reseller order placed"),
            @ApiResponse(responseCode = "400", description = "Insufficient balance, invalid/below-cost selling price, or validation error"),
            @ApiResponse(responseCode = "403", description = "User is not an approved reseller"),
            @ApiResponse(responseCode = "404", description = "Bundle not available")
    })
    public ResponseEntity<OrderResponse> placeResellerWalletOrder(
            @Valid @RequestBody WalletOrderRequest request,
            @RequestParam(required = false) BigDecimal sellingPrice) {

        UUID userId = currentUserId();

        // Controller-level guard: reject non-positive selling prices outright.
        // This does NOT replace the cost-floor check in OrderService — it
        // just stops obviously-malformed/malicious values (null omitted is
        // fine and handled downstream, but zero/negative never is) before
        // they're even logged as a legitimate attempt.
        if (sellingPrice != null && sellingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[ORDER] Rejected reseller order at controller level — " +
                            "non-positive sellingPrice: userId={} sellingPrice={}",
                    userId, sellingPrice);
            throw new com.databundleHum.OnetBundleHub.security.ValidationException(
                    "Selling price must be greater than zero.");
        }

        log.info("[ORDER] Reseller wallet order: userId={} phone={} network={} gb={} sellingPrice={}",
                userId, request.getPhoneNumber(), request.getNetwork(), request.getCapacityGb(), sellingPrice);
        OrderResponse response = orderService.placeResellerWalletOrder(userId, request, sellingPrice);
        log.info("[ORDER] Reseller order placed: userId={} orderId={} status={}",
                userId, response.getId(), response.getStatus());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ── Order history ─────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get paginated order history for the authenticated user")
    @ApiResponse(responseCode = "200", description = "Orders returned")
    public ResponseEntity<Page<OrderResponse>> getOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID userId = currentUserId();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        log.debug("[ORDER] Order history: userId={} page={} size={}", userId, page, size);
        return ResponseEntity.ok(orderService.getOrders(userId, pageable));
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("isAuthenticated()")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get a single order by ID (must belong to the authenticated user)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order returned"),
            @ApiResponse(responseCode = "403", description = "Order does not belong to user"),
            @ApiResponse(responseCode = "404", description = "Order not found")
    })
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long orderId) {
        UUID userId = currentUserId();
        log.debug("[ORDER] Get order: userId={} orderId={}", userId, orderId);
        return ResponseEntity.ok(orderService.getOrder(userId, orderId));
    }
}