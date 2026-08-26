package com.databundleHum.OnetBundleHub.controllers;

import com.databundleHum.OnetBundleHub.services.DataPrimoService;
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
 * bigDreamsService.fetchAvailableBundles() → dataPrimoService.fetchCatalog().
 *
 * IMPORTANT: unlike Big Dreams' BigDreamsBundleResponse (a clean, confirmed
 * DTO with sizeGb/buyingPriceGhc/etc.), DataPrimo's catalog field shape is
 * still UNCONFIRMED — see DataPrimoService.fetchCatalog()'s Javadoc. Rather
 * than guess field names into a typed DTO and risk silently mismapping
 * prices, this endpoint returns the raw catalog entries as-is for now.
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
@PreAuthorize("hasRole('ADMIN')")  // protect it — admin only
public class AdminBundleController {

    private final DataPrimoService dataPrimoService;

    /**
     * GET /api/admin/bundles
     * Fetch all available bundles from the DataPrimo catalog (raw entries —
     * see class Javadoc on why this isn't a typed DTO yet).
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllBundles() {
        log.info("[ADMIN-BUNDLES] Fetching full DataPrimo catalog");
        List<Map<String, Object>> bundles = dataPrimoService.fetchCatalog();
        log.info("[ADMIN-BUNDLES] Returned {} bundle(s)", bundles.size());
        return ResponseEntity.ok(bundles);
    }

    /**
     * GET /api/admin/bundles?network=mtn
     * Client-side filter over the full catalog by the raw "network" field —
     * exact expected values (mtn/telecel/airteltigo, or something else
     * entirely) are unconfirmed until a real catalog response is inspected.
     */
    @GetMapping(params = "network")
    public ResponseEntity<List<Map<String, Object>>> getBundlesByNetwork(
            @RequestParam String network) {
        log.info("[ADMIN-BUNDLES] Fetching DataPrimo catalog filtered by network={}", network);

        List<Map<String, Object>> all = dataPrimoService.fetchCatalog();
        List<Map<String, Object>> filtered = all.stream()
                .filter(entry -> {
                    String entryNetwork = dataPrimoService.extractNetwork(entry);
                    return entryNetwork != null && entryNetwork.equalsIgnoreCase(network);
                })
                .toList();

        log.info("[ADMIN-BUNDLES] network={} matched {} of {} bundle(s)",
                network, filtered.size(), all.size());
        return ResponseEntity.ok(filtered);
    }
}