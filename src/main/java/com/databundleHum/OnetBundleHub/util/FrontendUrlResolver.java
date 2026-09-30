package com.databundleHum.OnetBundleHub.util;

import com.databundleHum.OnetBundleHub.config.AppConfig;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Resolves the frontend's actual base URL for payment redirects from the
 * request Origin/Referer, falling back to AppConfig's hard-coded canonical
 * DataPack URL when neither header is present.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrontendUrlResolver {

    private final HttpServletRequest request;
    private final AppConfig appConfig;

    /** Returns a base URL with no trailing slash, e.g. "https://www.datapackk.shop". */
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
        log.debug("[FRONTEND-URL] No Origin/Referer header — falling back to canonical site URL: {}", fallback);
        return fallback;
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
