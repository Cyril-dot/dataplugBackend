package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.services.BigDreamsDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ── MIGRATION FROM BIG DREAMS TO DATAPRIMO (2026-08-26) ──────────────────────
 *
 * The admin catalog is read directly from Big Dreams' documented
 * get_bundles action and returns the typed provider listing.
 *
 * TODO: once a real GET /catalog response has been inspected (check the
 * logged raw body from fetchCatalog() on first real call), replace
 * List<Map<String, Object>> below with a proper DataPrimoBundleResponse DTO
 * mirroring BigDreamsBundleResponse's shape, and map fields explicitly here
 * or in DataPrimoService.
 *
 * The network filter param is also unconfirmed — DataPrimo's catalog does
 * not document a query-param filter the way Big Dreams' get_bundles did
 * (network=mtn|telecel|airteltigo). getBundlesByNetwork() below filters
 * client-side on whatever the "network" field turns out to be named, once
 * that's confirmed — for now it's a straight passthrough of the full catalog
 * with a warning logged if the filter can't be applied.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bundles")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")  // ✅ FIXED: was hasRole('ADMIN') — role is ROLE_SUPER_ADMIN
public class AdminBundleController {

    private final BigDreamsDataService bigDreamsDataService;

    /**
     * GET /api/admin/bundles
     * Fetch all available bundles from the DataPrimo catalog (raw entries —
     * see class Javadoc on why this isn't a typed DTO yet).
     */
    @GetMapping
    public ResponseEntity<List<BigDreamsDataService.BundleListing>> getAllBundles() {
        log.info("[ADMIN-BUNDLES] Fetching full DataPrimo catalog");
        List<BigDreamsDataService.BundleListing> bundles = bigDreamsDataService.getBundles(null);
        log.info("[ADMIN-BUNDLES] Returned {} bundle(s)", bundles.size());
        return ResponseEntity.ok(bundles);
    }

    /**
     * GET /api/admin/bundles?network=mtn
     * Client-side filter over the full catalog by the raw "network" field —
     * exact expected values (mtn/telecel/airteltigo, or something else
     * entirely) are unconfirmed until a real catalog response is inspected.
     */
    /**
     * GET /api/admin/big-dreams/balance
     * Read the platform's Big Dreams provider wallet balance. The API key
     * remains server-side; this endpoint is restricted to SUPER_ADMIN.
     */
    @GetMapping("/big-dreams/balance")
    public ResponseEntity<BigDreamsDataService.BalanceResult> getBigDreamsBalance() {
        return ResponseEntity.ok(bigDreamsDataService.checkBalance());
    }

    @GetMapping(params = "network")
    public ResponseEntity<List<BigDreamsDataService.BundleListing>> getBundlesByNetwork(
            @RequestParam String network) {
        log.info("[ADMIN-BUNDLES] Fetching DataPrimo catalog filtered by network={}", network);

        String providerNetwork = network.equalsIgnoreCase("airteltigo") ? "ishare" : network.toLowerCase();
        List<BigDreamsDataService.BundleListing> all = bigDreamsDataService.getBundles(null);
        List<BigDreamsDataService.BundleListing> filtered = all.stream()
                .filter(entry -> entry.network() != null && entry.network().equalsIgnoreCase(providerNetwork))
                .toList();

        log.info("[ADMIN-BUNDLES] network={} matched {} of {} bundle(s)",
                network, filtered.size(), all.size());
        return ResponseEntity.ok(filtered);
    }
}