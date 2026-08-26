package com.databundleHum.OnetBundleHub.security;

public class UpstreamApiException extends AppException {
    public UpstreamApiException(String message) {
        super(message);
    }

    public UpstreamApiException(String message, Throwable cause) {
        super(message);
        if (cause != null) {
            initCause(cause);
        }
    }
}