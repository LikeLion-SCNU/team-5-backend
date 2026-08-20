package org.example.naeilbank.global.exception;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

public final class CorrelationId {
    public static final String HEADER_NAME = "X-Correlation-Id";
    private static final String REQUEST_ATTRIBUTE = CorrelationId.class.getName() + ".value";
    private static final int MAX_LENGTH = 128;
    private static final String SAFE_PATTERN = "[A-Za-z0-9._:-]+";

    private CorrelationId() {
    }

    public static String resolve(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ATTRIBUTE);
        if (existing instanceof String correlationId) {
            return correlationId;
        }

        String header = request.getHeader(HEADER_NAME);
        String correlationId = isSafe(header) ? header : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        return correlationId;
    }

    private static boolean isSafe(String value) {
        return value != null && value.length() <= MAX_LENGTH && value.matches(SAFE_PATTERN);
    }
}
