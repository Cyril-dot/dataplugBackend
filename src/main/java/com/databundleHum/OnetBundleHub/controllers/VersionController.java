package com.databundleHum.OnetBundleHub.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Deploy marker. Lets anyone confirm which backend build is actually live
 * without needing auth — added alongside the 2026-10-23 storefront/payment
 * fixes (guest checker 401, storefront fulfilment routing, provisioning
 * status, abandoned-order retirement) after a push reached GitHub but the
 * running server kept serving the old build with no way to tell from
 * outside. Bump {@code build} whenever a deploy needs to be verifiable.
 */
@RestController
@RequestMapping("/api/v1")
public class VersionController {

    @GetMapping("/version")
    public ResponseEntity<Map<String, String>> version() {
        return ResponseEntity.ok(Map.of(
                "service", "dataplug-backend",
                "build", "2026-10-23-emma-webhook"
        ));
    }
}
