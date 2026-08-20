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
                && !path.equals("/api/v1/auth/kakao")
                && !path.equals("/api/v1/auth/join")
                && !path.equals("/api/v1/auth/email/verify")
                && !path.equals("/api/v1/auth/email/resend");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = clientIdentity(request) + ":" + request.getRequestURI();
        Instant now = clock.instant();
        evictExpiredAndBoundedBuckets(key, now);
        Bucket bucket = buckets.compute(key, (ignored, current) -> nextBucket(current, now));

        if (bucket.count > properties.capacity()) {
            responseWriter.write(request, response, ErrorCode.TOO_MANY_REQUESTS);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 레이트리밋 버킷을 실제 클라이언트별로 나눈다.
     *
     * 요청은 Caddy → nginx → 애플리케이션 순으로 들어오고 두 프록시가 각각 X-Forwarded-For에
     * 자기가 본 주소를 덧붙인다. 따라서 목록의 마지막은 nginx가 본 Caddy 주소이고, 그 앞이
     * Caddy가 본 실제 클라이언트다. 클라이언트가 헤더를 위조해도 앞쪽에만 쌓이므로
     * 뒤에서 두 번째 값은 신뢰할 수 있다. 프록시를 거치지 않은 요청은 소켓 주소를 그대로 쓴다.
     */
    private String clientIdentity(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] hops = forwarded.split(",");
        if (hops.length < 2) {
            // 프록시가 덧붙인 흔적이 없으면 클라이언트가 직접 붙인 헤더이므로 믿지 않는다.
            return request.getRemoteAddr();
        }
        String candidate = hops[hops.length - 2].trim();
        return candidate.isEmpty() ? request.getRemoteAddr() : candidate;
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
