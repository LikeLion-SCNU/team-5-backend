package org.example.naeilbank.auth;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.QueueDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "app.auth.rate-limit.capacity=50",
        "app.auth.rate-limit.window=1m"
})
class AuthKakaoApiIntegrationTest extends KakaoAuthIntegrationSupport {

    @Test
    void kakaoLoginUsesOAuthContractAndNormalizesProviderFailures() throws Exception {
        String email = "kakao-" + UUID.randomUUID() + "@example.com";
        enqueueKakaoLogin("""
                {
                  "id": 12345,
                  "properties": {"nickname": "kakao-user"},
                  "kakao_account": {
                    "email": "%s",
                    "is_email_valid": true,
                    "is_email_verified": true
                  }
                }
                """.formatted(email.toUpperCase()));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "valid-code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("ROLE_USER"));

        Map<String, Object> kakaoUser = jdbcTemplate.queryForMap(
                "select auth_provider, nickname, kakao_id from users where email = ?",
                email
        );
        assertThat(kakaoUser.get("auth_provider")).isEqualTo("kakao");
        assertThat(kakaoUser.get("nickname")).isEqualTo("kakao-user");
        assertThat(kakaoUser.get("kakao_id")).isEqualTo("12345");

        KAKAO.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));
        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "bad-code"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_AUTH_FAILED"));
    }

    @Test
    void kakaoLoginUsesDeterministicFallbackEmailWhenProviderEmailIsAbsentOrUnusable() throws Exception {
        assertKakaoFallbackEmail(4101, """
                {"id": 4101, "properties": {"nickname": "missing-account"}}
                """);
        assertKakaoFallbackEmail(4102, """
                {"id": 4102, "properties": {"nickname": "without-email"}, "kakao_account": {}}
                """);
        assertKakaoFallbackEmail(4103, """
                {"id": 4103, "properties": {"nickname": "blank-email"}, "kakao_account": {"email": "   "}}
                """);
        assertKakaoFallbackEmail(4104, """
                {
                  "id": 4104,
                  "properties": {"nickname": "unverified-email"},
                  "kakao_account": {"email": "unverified@example.com", "is_email_valid": true, "is_email_verified": false}
                }
                """);
        assertKakaoFallbackEmail(4105, """
                {
                  "id": 4105,
                  "properties": {"nickname": "missing-flags"},
                  "kakao_account": {"email": "missing-flags@example.com"}
                }
                """);
        assertKakaoFallbackEmail(4106, """
                {
                  "id": 4106,
                  "properties": {"nickname": "string-flags"},
                  "kakao_account": {"email": "string-flags@example.com", "is_email_valid": "true", "is_email_verified": true}
                }
                """);
    }

    @Test
    void kakaoLoginReusesSameKakaoIdAndSeparatesDifferentIds() throws Exception {
        JsonNode first = kakaoLogin("""
                {"id": 4201, "properties": {"nickname": "first"}}
                """);
        JsonNode repeated = kakaoLogin("""
                {
                  "id": 4201,
                  "properties": {"nickname": "repeat"},
                  "kakao_account": {
                    "email": "changed@example.com",
                    "is_email_valid": true,
                    "is_email_verified": true
                  }
                }
                """);
        JsonNode other = kakaoLogin("""
                {"id": 4202, "properties": {"nickname": "other"}}
                """);

        assertThat(repeated.at("/user/id").asText()).isEqualTo(first.at("/user/id").asText());
        assertThat(other.at("/user/id").asText()).isNotEqualTo(first.at("/user/id").asText());
        assertThat(first.at("/user/email").asText()).isEqualTo("kakao_4201@users.invalid");
        assertThat(other.at("/user/email").asText()).isEqualTo("kakao_4202@users.invalid");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from users where kakao_id = ?",
                Integer.class,
                "4201"
        )).isEqualTo(1);
    }

    @Test
    void concurrentFirstLoginForSameKakaoIdCreatesSingleUser() throws Exception {
        int attempts = 2;
        KAKAO.setDispatcher(concurrentLoginDispatcher());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        try {
            Callable<String> login = () -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent login start timed out");
                }
                return kakaoLoginWithCode("concurrent-code").at("/user/id").asText();
            };
            Future<String> first = executor.submit(login);
            Future<String> second = executor.submit(login);

            start.countDown();
            LinkedHashSet<String> userIds = new LinkedHashSet<>();
            userIds.add(first.get(10, TimeUnit.SECONDS));
            userIds.add(second.get(10, TimeUnit.SECONDS));

            assertThat(userIds).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from users where kakao_id = ?",
                    Integer.class,
                    "4401"
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            KAKAO.setDispatcher(new QueueDispatcher());
        }
    }

    @Test
    void kakaoLoginFailsClosedWhenFallbackEmailCollidesWithExistingAccount() throws Exception {
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                UUID.randomUUID(),
                "kakao_4301@users.invalid"
        );
        enqueueKakaoLogin("""
                {"id": 4301, "properties": {"nickname": "collision"}}
                """);

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "collision-code"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_AUTH_FAILED"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from users where kakao_id = ?",
                Integer.class,
                "4301"
        )).isZero();
    }

    @Test
    void kakaoLoginFailsClosedWhenProviderEmailCollidesWithLocalAccountIgnoringCase() throws Exception {
        String email = "Local-Collision-" + UUID.randomUUID() + "@Example.com";
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                UUID.randomUUID(),
                email
        );
        enqueueKakaoLogin("""
                {
                  "id": 4302,
                  "properties": {"nickname": "real-email-collision"},
                  "kakao_account": {
                    "email": "%s",
                    "is_email_valid": true,
                    "is_email_verified": true
                  }
                }
                """.formatted(email.toLowerCase()));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "real-collision-code"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_AUTH_FAILED"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from users where kakao_id = ?",
                Integer.class,
                "4302"
        )).isZero();
    }

    @Test
    void kakaoLoginFailsClosedForInvalidProviderPayloadAndLegacyGet() throws Exception {
        assertInvalidKakaoPayload("""
                {"properties": {"nickname": "missing-id"}}
                """);
        assertInvalidKakaoPayload("""
                {"id": "4303", "properties": {"nickname": "string-id"}}
                """);
        assertInvalidKakaoPayload("""
                {"id": 4304.5, "properties": {"nickname": "fraction-id"}}
                """);
        assertInvalidKakaoPayload("""
                {"id": 0, "properties": {"nickname": "zero-id"}}
                """);
        assertInvalidKakaoPayload("""
                {"id": -1, "properties": {"nickname": "negative-id"}}
                """);

        mockMvc.perform(get("/api/v1/auth/kakao"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

}
