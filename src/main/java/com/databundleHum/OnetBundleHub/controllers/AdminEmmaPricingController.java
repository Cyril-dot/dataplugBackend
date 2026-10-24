package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.entity.PlatformSettings;
import com.databundleHum.OnetBundleHub.repos.PlatformSettingsRepository;
import com.databundleHum.OnetBundleHub.services.EmmaDataKingService;
import com.databundleHum.OnetBundleHub.services.EmmaDataKingService.PackageOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * ── EMMA DATA KING ADMIN PRICING ─────────────────────────────────────────────
 *
 * The admin counterpart to {@link AdminBundleController} (Big Dreams), but for
 * the live Emma provider that now fulfils data orders.
 *
 * Each row is one sellable platform bundle (a {@link PlatformSettings} row)
 * enriched with Emma's live cheapest in-stock cost for the same network +
 * size, so the admin can see the real margin and set the platform prices
 * (public + reseller) from one place. Per-row saves reuse the existing
 * PUT /api/v1/admin/pricing upsert; POST /sync bulk-sets every reseller
 * price to its Emma cost, skipping rows Emma does not stock or where the
 * Emma cost is not below the public price (the upsert's own guard).
 *
 * SUPER_ADMIN only, mirroring AdminBundleController.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/emma-pricing")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminEmmaPricingController {

    private final EmmaDataKingService emmaDataKingService;
    private final PlatformSettingsRepository platformSettingsRepository;

    /** One platform bundle + Emma's live cost for it (null when not stocked). */
    public record EmmaPricingRow(
            String network,
            BigDecimal capacityGb,
            BigDecimal emmaCostGhc,
            String emmaFamily,
            Long emmaPackageId,
            boolean emmaAvailable,
            BigDecimal resellerPriceGhc,
            BigDecimal publicPriceGhc,
            boolean active) {}

    /** Same shape as the Big Dreams balance type, so the admin UI reuses it. */
    public record EmmaBalanceResponse(
            BigDecimal balance,
            String currency,
            String accountName,
            String accountEmail) {}

    public record SyncSkipped(String network, BigDecimal capacityGb, String reason) {}
    public record SyncResponse(int updated, List<SyncSkipped> skipped) {}

    private static final List<PlatformSettings.Network> NETWORK_ORDER = List.of(
            PlatformSettings.Network.MTN,
            PlatformSettings.Network.TELECEL,
            PlatformSettings.Network.AIRTELTIGO);

    /**
     * GET /api/admin/emma-pricing — every platform pricing row with Emma's
     * live cheapest in-stock cost alongside the configured prices.
     */
    @GetMapping
    public ResponseEntity<List<EmmaPricingRow>> getEmmaPricing() {
        List<EmmaPricingRow> rows = platformSettingsRepository.findAll().stream()
                .map(settings -> {
                    Optional<PackageOption> pkg = emmaDataKingService.findPackage(
                            settings.getNetwork(), settings.getCapacityGb());
                    return new EmmaPricingRow(
                            settings.getNetwork().name(),
                            settings.getCapacityGb(),
                            pkg.map(PackageOption::price).orElse(null),
                            pkg.map(PackageOption::networkCode).orElse(null),
                            pkg.map(PackageOption::packageId).orElse(null),
                            pkg.isPresent(),
                            settings.getResellerPriceGhc(),
                            settings.getPublicPriceGhc(),
                            settings.isActive());
                })
                .sorted(Comparator
                        .comparingInt((EmmaPricingRow r) -> NETWORK_ORDER.indexOf(
                                PlatformSettings.Network.valueOf(r.network())))
                        .thenComparing(EmmaPricingRow::capacityGb))
                .toList();
        log.info("[ADMIN-EMMA-PRICING] Returned {} row(s)", rows.size());
        return ResponseEntity.ok(rows);
    }

    /**
     * GET /api/admin/emma-pricing/balance — the platform's Emma wallet
     * balance. The API key remains server-side; SUPER_ADMIN only.
     */
    @GetMapping("/balance")
    public ResponseEntity<EmmaBalanceResponse> getEmmaBalance() {
        EmmaDataKingService.BalanceResult balance = emmaDataKingService.checkBalance();
        return ResponseEntity.ok(new EmmaBalanceResponse(
                balance.balance(), balance.currency(), balance.accountName(), balance.email()));
    }

    /**
     * POST /api/admin/emma-pricing/sync — set every active row's reseller
     * price to its live Emma cost. Rows Emma does not stock, and rows whose
     * Emma cost is not below the public price, are left untouched and
     * reported as skipped so the admin can fix them by hand.
     */
    @PostMapping("/sync")
    @Transactional
    public ResponseEntity<SyncResponse> syncPricingToEmmaCosts() {
        int updated = 0;
        List<SyncSkipped> skipped = new ArrayList<>();
        for (PlatformSettings settings : platformSettingsRepository.findAll()) {
            if (!settings.isActive()) {
                continue;
            }
            Optional<PackageOption> pkg = emmaDataKingService.findPackage(
                    settings.getNetwork(), settings.getCapacityGb());
            if (pkg.isEmpty()) {
                skipped.add(new SyncSkipped(settings.getNetwork().name(), settings.getCapacityGb(),
                        "Emma does not stock this size right now"));
                continue;
            }
            BigDecimal emmaCost = pkg.get().price();
            if (emmaCost.compareTo(settings.getPublicPriceGhc()) >= 0) {
                skipped.add(new SyncSkipped(settings.getNetwork().name(), settings.getCapacityGb(),
                        "Emma cost GHS " + emmaCost + " is not below the public price GHS "
                                + settings.getPublicPriceGhc()));
                continue;
            }
            settings.setResellerPriceGhc(emmaCost);
            platformSettingsRepository.save(settings);
            updated++;
            log.info("[ADMIN-EMMA-PRICING] Synced {} {}GB reseller price to Emma cost {}",
                    settings.getNetwork(), settings.getCapacityGb(), emmaCost);
        }
        return ResponseEntity.ok(new SyncResponse(updated, skipped));
    }
}
