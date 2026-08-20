package org.example.naeilbank.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.global.config.properties.AuthRateLimitProperties;
import org.example.naeilbank.global.exception.SecurityErrorResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitFilterTest {
    @Test
    void evictsExpiredBucketsAndKeepsABoundedBucketMap() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T00:00:00Z"));
        AuthRateLimitFilter filter = new AuthRateLimitFilter(
                new AuthRateLimitProperties(10, Duration.ofSeconds(1), 2),
                new SecurityErrorResponseWriter(new ObjectMapper()),
                clock
        );

        perform(filter, "198.51.100.1", null);
        perform(filter, "198.51.100.2", null);
        assertThat(filter.bucketCountForTesting()).isEqualTo(2);

        perform(filter, "198.51.100.3", null);
        assertThat(filter.bucketCountForTesting()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(2));
        perform(filter, "198.51.100.4", null);
        assertThat(filter.bucketCountForTesting()).isEqualTo(1);
    }

    @Test
    void ignoresForwardedForWhenRateLimiting() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T00:00:00Z"));
        AuthRateLimitFilter filter = new AuthRateLimitFilter(
                new AuthRateLimitProperties(1, Duration.ofMinutes(1), 10),
                new SecurityErrorResponseWriter(new ObjectMapper()),
                clock
        );

        MockHttpServletResponse first = perform(filter, "198.51.100.7", "203.0.113.10");
        MockHttpServletResponse second = perform(filter, "198.51.100.7", "203.0.113.11");

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getContentAsString()).contains("TOO_MANY_REQUESTS");
    }

    private MockHttpServletResponse perform(AuthRateLimitFilter filter, String remoteAddr, String forwardedFor)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Z");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
