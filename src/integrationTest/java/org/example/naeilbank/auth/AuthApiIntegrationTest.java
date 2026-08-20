package org.example.naeilbank.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.example.naeilbank.domain.auth.service.RefreshTokenHasher;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class AuthApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("naeil_bank_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    static final MockWebServer KAKAO = startKakaoServer();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("kakao.token-uri", () -> KAKAO.url("/oauth/token").toString());
        registry.add("kakao.user-info-uri", () -> KAKAO.url("/v2/user/me").toString());
    }

    @AfterAll
    static void stopKakaoServer() throws IOException {
        KAKAO.shutdown();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RefreshTokenHasher refreshTokenHasher;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanAuthTables() {
        jdbcTemplate.update("delete from users");
    }

    @Test
    void signupLoginRotationReplayAndLogoutPersistExpectedRefreshTokenState() throws Exception {
        assertAuthSchemaMetadataMatchesRepositoryContract();
        String email = "flow-" + UUID.randomUUID() + "@example.com";

        String joinBody = objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", "password-123"
        ));
        JsonNode join = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(joinBody))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn().getResponse().getContentAsString());
        UUID userId = UUID.fromString(join.get("userId").asText());

        Map<String, Object> userRow = jdbcTemplate.queryForMap(
                "select email, password_hash, auth_provider, role from users where id = ?",
                userId
        );
        assertThat(userRow.get("email")).isEqualTo(email);
        assertThat(userRow.get("password_hash")).isNotEqualTo("password-123");
        assertThat(userRow.get("auth_provider")).isEqualTo("email");
        assertThat(userRow.get("role")).isEqualTo("USER");

        JsonNode login = login(email, "password-123");
        String firstRefresh = login.get("refreshToken").asText();
        assertThat(login.get("accessToken").asText()).isNotBlank();
        assertThat(login.at("/user/role").asText()).isEqualTo("ROLE_USER");
        assertThat(login.get("expiresIn").asLong()).isEqualTo(1800);

        String firstHash = refreshTokenHasher.hash(firstRefresh);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ?",
                Integer.class,
                firstHash
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ?",
                Integer.class,
                firstRefresh
        )).isZero();

        JsonNode rotated = objectMapper.readTree(refresh(firstRefresh, status().isOk())
                .andReturn().getResponse().getContentAsString());
        String secondRefresh = rotated.get("refreshToken").asText();
        String secondHash = refreshTokenHasher.hash(secondRefresh);

        Map<String, Object> firstTokenRow = jdbcTemplate.queryForMap(
                "select family_id, used_at, revoked_at from refresh_tokens where token_hash = ?",
                firstHash
        );
        Map<String, Object> secondTokenRow = jdbcTemplate.queryForMap(
                "select family_id, previous_token_hash, revoked_at from refresh_tokens where token_hash = ?",
                secondHash
        );
        assertThat(firstTokenRow.get("used_at")).isNotNull();
        assertThat(firstTokenRow.get("revoked_at")).isNotNull();
        assertThat(secondTokenRow.get("previous_token_hash")).isEqualTo(firstHash);
        assertThat(secondTokenRow.get("family_id")).isEqualTo(firstTokenRow.get("family_id"));
        assertThat(secondTokenRow.get("revoked_at")).isNull();

        refresh(firstRefresh, status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
        Map<String, Object> replayedTokenRow = jdbcTemplate.queryForMap(
                "select reuse_detected_at from refresh_tokens where token_hash = ?",
                firstHash
        );
        assertThat(replayedTokenRow.get("reuse_detected_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where family_id = ? and revoked_at is null",
                Integer.class,
                firstTokenRow.get("family_id")
        )).isZero();

        JsonNode secondLogin = login(email, "password-123");
        String logoutRefresh = secondLogin.get("refreshToken").asText();
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", logoutRefresh))))
                .andExpect(status().isNoContent());
        refresh(logoutRefresh, status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
    }

    @Test
    void malformedExpiredAndCredentialFailuresUseSpecificAuthErrors() throws Exception {
        String email = "failure-" + UUID.randomUUID() + "@example.com";
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                        insert into users (id, email, password_hash, auth_provider, role)
                        values (?, ?, ?, 'email', 'USER')
                        """,
                userId,
                email,
                passwordEncoder.encode("password-123")
        );

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "wrong-password"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        refresh("not a token", status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        String expiredRawToken = "expiredRefreshTokenValue_000000000000000000000000";
        jdbcTemplate.update("""
                        insert into refresh_tokens (user_id, token_hash, family_id, expires_at)
                        values (?, ?, ?, ?)
                        """,
                userId,
                refreshTokenHasher.hash(expiredRawToken),
                UUID.randomUUID(),
                OffsetDateTime.parse("2020-01-01T00:00:00Z")
        );
        refresh(expiredRawToken, status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_EXPIRED"));
        assertThat(jdbcTemplate.queryForObject(
                "select revoked_at is not null from refresh_tokens where token_hash = ?",
                Boolean.class,
                refreshTokenHasher.hash(expiredRawToken)
        )).isTrue();
    }

    @Test
    void authorizationRolesValidationCorsRateLimitAndMethodContractsHold() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                userId,
                "user-" + UUID.randomUUID() + "@example.com"
        );
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'ADMIN')",
                adminId,
                "admin-" + UUID.randomUUID() + "@example.com"
        );

        String userAccessToken = jwtTokenProvider.createToken(userId, "user@example.com", "USER");
        String adminAccessToken = jwtTokenProvider.createToken(adminId, "admin@example.com", "ADMIN");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ROLE_USER"));
        mockMvc.perform(get("/api/admin/probe").header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/admin/probe").header("Authorization", "Bearer " + adminAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "auth-validation-test")
                        .content(objectMapper.writeValueAsString(Map.of("email", "bad", "password", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Correlation-Id", "auth-validation-test"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(get("/api/v1/auth/join"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(request -> {
                                request.setRemoteAddr("198.51.100.77");
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", "rate-" + i + "@example.com",
                                    "password", "password-123"
                            ))))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.77");
                            request.addHeader("X-Forwarded-For", "203.0.113.1");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "rate-final@example.com",
                                "password", "password-123"
                        ))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    @Test
    void kakaoLoginUsesOAuthContractAndNormalizesProviderFailures() throws Exception {
        String email = "kakao-" + UUID.randomUUID() + "@example.com";
        KAKAO.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"kakao-access-token\"}"));
        KAKAO.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "id": 12345,
                          "properties": {"nickname": "kakao-user"},
                          "kakao_account": {"email": "%s"}
                        }
                        """.formatted(email)));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "valid-code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("ROLE_USER"));

        Map<String, Object> kakaoUser = jdbcTemplate.queryForMap(
                "select auth_provider, nickname from users where email = ?",
                email
        );
        assertThat(kakaoUser.get("auth_provider")).isEqualTo("kakao");
        assertThat(kakaoUser.get("nickname")).isEqualTo("kakao-user");

        KAKAO.enqueue(new MockResponse().setResponseCode(401).setBody("{}"));
        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "bad-code"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_AUTH_FAILED"));
    }

    private JsonNode login(String email, String password) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions refresh(
            String refreshToken,
            org.springframework.test.web.servlet.ResultMatcher expectedStatus
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(expectedStatus);
    }

    private void assertAuthSchemaMetadataMatchesRepositoryContract() {
        assertThat(jdbcTemplate.queryForObject("""
                        select data_type
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'users'
                          and column_name = 'id'
                        """, String.class))
                .isEqualTo("uuid");
        assertThat(jdbcTemplate.queryForObject("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'users'
                          and column_name in ('password_hash', 'auth_provider', 'role')
                        """, Integer.class))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'refresh_tokens'
                          and column_name in ('family_id', 'previous_token_hash', 'used_at', 'reuse_detected_at')
                        """, Integer.class))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                        select count(*)
                        from pg_constraint
                        where conrelid = 'public.refresh_tokens'::regclass
                          and conname = 'refresh_tokens_token_hash_key'
                        """, Integer.class))
                .isEqualTo(1);
    }

    private static MockWebServer startKakaoServer() {
        try {
            MockWebServer server = new MockWebServer();
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start Kakao mock server", e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdminProbeConfiguration {
        @Bean
        AdminProbeController adminProbeController() {
            return new AdminProbeController();
        }
    }

    @RestController
    static class AdminProbeController {
        @GetMapping("/api/admin/probe")
        Map<String, String> probe() {
            return Map.of("status", "ok");
        }
    }
}
