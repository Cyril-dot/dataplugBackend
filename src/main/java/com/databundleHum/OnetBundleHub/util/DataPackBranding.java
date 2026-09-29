package com.databundleHum.OnetBundleHub.util;

import java.util.regex.Pattern;

/** Keeps external provider branding out of customer- and admin-facing messages. */
public final class DataPackBranding {

    private static final Pattern PROVIDER_NAME = Pattern.compile("(?i)Big\\s*Dreams(?:\\s*Data)?");

    private DataPackBranding() {}

    public static String forDisplay(String message) {
        return message == null ? null : PROVIDER_NAME.matcher(message).replaceAll("DataPack");
    }
}
