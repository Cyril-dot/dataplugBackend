package com.databundleHum.OnetBundleHub.util;

import com.databundleHum.OnetBundleHub.config.AppConfig;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Resolves the frontend's actual base URL for building payment redirect
 * URLs, instead of relying solely on the static app.base-url config
 * property.
 *
 * ── Why this exists ──────────────────────────────────────────────────────
 * app.base-url defaults to a hardcoded fallback (https://www.databaygh.shop)
 * when the APP_BASE_URL env var isn't set on whatever platform is hosting
 * this backend. That's easy to forget to update — exactly what happened
 * moving from the old host to Railway/Vercel, silently sending Korapay
 * redirect URLs pointing at a stale domain. Browsers automatically send an
 * Origin header on cross-origin requests (which every call from the
 * Vercel-hosted frontend to this Railway-hosted backend is), so reading
 * that header resolves the ACTUAL calling frontend's URL dynamically —
 * correct for local dev, Vercel preview deployments, and production alike,
 * with zero config to keep in sync. Falls back to app.base-url only for
 * non-browser calls where no Origin/Referer header is present at all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrontendUrlResolver {

    private final HttpServletRequest request;
    private final AppConfig appConfig;

    /** Returns a base URL with no trailing slash, e.g. "https://datapack-lac.vercel.app". */
    public String resolveBaseUrl() {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            log.debug("[FRONTEND-URL] Resolved from Origin header: {}", origin);
            return stripTrailingSlash(origin);
        }

        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            try {
                URL url = new URL(referer);
                String base = url.getProtocol() + "://" + url.getAuthority();
                log.debug("[FRONTEND-URL] Resolved from Referer header: {} -> {}", referer, base);
                return base;
            } catch (MalformedURLException ex) {
                log.warn("[FRONTEND-URL] Referer header present but unparsable: {}", referer);
            }
        }

        String fallback = stripTrailingSlash(appConfig.getAppBaseUrl());
        log.debug("[FRONTEND-URL] No Origin/Referer header — falling back to app.base-url: {}", fallback);
        return fallback;
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
