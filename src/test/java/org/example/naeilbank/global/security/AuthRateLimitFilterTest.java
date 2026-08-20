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
    void separatesBucketsPerRealClientBehindTheProxyChain() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T00:00:00Z"));
        AuthRateLimitFilter filter = new AuthRateLimitFilter(
                new AuthRateLimitProperties(1, Duration.ofMinutes(1), 10),
                new SecurityErrorResponseWriter(new ObjectMapper()),
                clock
        );

        // Caddy → nginx를 거치면 마지막 값은 nginx가 본 Caddy 주소이고 그 앞이 실제 클라이언트다.
        MockHttpServletResponse firstClient = perform(filter, "172.17.0.1", "203.0.113.10, 172.17.0.2");
        MockHttpServletResponse otherClient = perform(filter, "172.17.0.1", "203.0.113.11, 172.17.0.2");
        MockHttpServletResponse firstClientAgain = perform(filter, "172.17.0.1", "203.0.113.10, 172.17.0.2");

        assertThat(firstClient.getStatus()).isEqualTo(200);
        assertThat(otherClient.getStatus()).as("다른 사용자는 앞선 사용자 때문에 막히지 않는다").isEqualTo(200);
        assertThat(firstClientAgain.getStatus()).isEqualTo(429);
        assertThat(firstClientAgain.getContentAsString()).contains("TOO_MANY_REQUESTS");
    }

    @Test
    void spoofedForwardedForCannotMintNewBuckets() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T00:00:00Z"));
        AuthRateLimitFilter filter = new AuthRateLimitFilter(
                new AuthRateLimitProperties(1, Duration.ofMinutes(1), 10),
                new SecurityErrorResponseWriter(new ObjectMapper()),
                clock
        );

        // 클라이언트가 헤더를 위조해도 프록시가 덧붙인 값은 뒤쪽에 남아 식별자가 바뀌지 않는다.
        MockHttpServletResponse first = perform(filter, "172.17.0.1", "203.0.113.10, 172.17.0.2");
        MockHttpServletResponse spoofed = perform(filter, "172.17.0.1", "9.9.9.9, 203.0.113.10, 172.17.0.2");

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(spoofed.getStatus()).isEqualTo(429);
    }

    @Test
    void ignoresForwardedForWithoutAProxyHop() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T00:00:00Z"));
        AuthRateLimitFilter filter = new AuthRateLimitFilter(
                new AuthRateLimitProperties(1, Duration.ofMinutes(1), 10),
                new SecurityErrorResponseWriter(new ObjectMapper()),
                clock
        );

        // 프록시를 거치지 않은 요청이 헤더만 붙여도 새 버킷을 만들 수 없다.
        assertThat(perform(filter, "198.51.100.77", null).getStatus()).isEqualTo(200);
        assertThat(perform(filter, "198.51.100.77", "203.0.113.1").getStatus()).isEqualTo(429);
    }

    @Test
    void fallsBackToSocketAddressWithoutProxyHeaders() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T00:00:00Z"));
        AuthRateLimitFilter filter = new AuthRateLimitFilter(
                new AuthRateLimitProperties(1, Duration.ofMinutes(1), 10),
                new SecurityErrorResponseWriter(new ObjectMapper()),
                clock
        );

        assertThat(perform(filter, "198.51.100.7", null).getStatus()).isEqualTo(200);
        assertThat(perform(filter, "198.51.100.7", null).getStatus()).isEqualTo(429);
        assertThat(perform(filter, "198.51.100.8", null).getStatus()).isEqualTo(200);
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
