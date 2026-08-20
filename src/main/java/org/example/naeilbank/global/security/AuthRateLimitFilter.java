package org.example.naeilbank.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.naeilbank.global.config.properties.AuthRateLimitProperties;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.global.exception.SecurityErrorResponseWriter;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private final AuthRateLimitProperties properties;
    private final SecurityErrorResponseWriter responseWriter;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !path.equals("/api/v1/auth/login")
                && !path.equals("/api/v1/auth/refresh")
                && !path.equals("/api/v1/auth/kakao");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = request.getRemoteAddr() + ":" + request.getRequestURI();
        Instant now = clock.instant();
        evictExpiredAndBoundedBuckets(key, now);
        Bucket bucket = buckets.compute(key, (ignored, current) -> nextBucket(current, now));

        if (bucket.count > properties.capacity()) {
            responseWriter.write(request, response, ErrorCode.TOO_MANY_REQUESTS);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Bucket nextBucket(Bucket current, Instant now) {
        if (current == null || !now.isBefore(current.windowStartedAt.plus(properties.window()))) {
            return new Bucket(now, 1);
        }
        return new Bucket(current.windowStartedAt, current.count + 1);
    }

    private void evictExpiredAndBoundedBuckets(String incomingKey, Instant now) {
        buckets.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().windowStartedAt.plus(properties.window())));
        while (buckets.size() >= properties.maxBuckets() && !buckets.containsKey(incomingKey)) {
            buckets.entrySet().stream()
                    .min((left, right) -> left.getValue().windowStartedAt.compareTo(right.getValue().windowStartedAt))
                    .map(Map.Entry::getKey)
                    .ifPresentOrElse(buckets::remove, () -> {});
        }
    }

    int bucketCountForTesting() {
        return buckets.size();
    }

    private record Bucket(Instant windowStartedAt, int count) {
    }
}
