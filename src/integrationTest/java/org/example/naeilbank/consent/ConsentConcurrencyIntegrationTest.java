package org.example.naeilbank.consent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@Testcontainers
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class ConsentConcurrencyIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("naeil_bank_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Test
    void concurrentFirstCreateWithSameKeyProducesOneConsentAndOneAudit() throws Exception {
        UUID userId = createUser();
        String token = accessToken(userId);
        String body = objectMapper.writeValueAsString(Map.of(
                "granted", true,
                "consentVersion", 1,
                "textHash", "a".repeat(64),
                "expectedVersion", 0,
                "idempotencyKey", "concurrent-" + UUID.randomUUID()
        ));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> change(token, body, ready, start));
            var second = executor.submit(() -> change(token, body, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("both consent requests should become ready")
                    .isTrue();
            start.countDown();

            List<JsonNode> responses = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertThat(responses).extracting(response -> response.get("replayed").asBoolean())
                    .containsExactlyInAnyOrder(false, true);
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from consents where user_id = ? and purpose = 'MEAL_AI'",
                Integer.class,
                userId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where user_id = ? and event_type = 'CONSENT_CHANGED'",
                Integer.class,
                userId
        )).isEqualTo(1);
    }

    @Test
    void concurrentFirstCreateWithDistinctKeysRejectsOneAsStale() throws Exception {
        UUID userId = createUser();
        String token = accessToken(userId);
        String firstBody = changeBody("first-" + UUID.randomUUID());
        String secondBody = changeBody("second-" + UUID.randomUUID());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> rawChange(token, firstBody, ready, start));
            var second = executor.submit(() -> rawChange(token, secondBody, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("both consent requests should become ready")
                    .isTrue();
            start.countDown();

            List<ChangeResult> responses = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertThat(responses).extracting(ChangeResult::status)
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(responses.stream()
                    .filter(response -> response.status() == 409)
                    .findFirst()
                    .orElseThrow()
                    .body()
                    .get("code")
                    .asText()).isEqualTo("CONSENT_VERSION_CONFLICT");
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from consents where user_id = ? and purpose = 'MEAL_AI'",
                Integer.class,
                userId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where user_id = ? and event_type = 'CONSENT_CHANGED'",
                Integer.class,
                userId
        )).isEqualTo(1);
    }

    private JsonNode change(
            String token,
            String body,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ChangeResult result = rawChange(token, body, ready, start);
        assertThat(result.status()).isEqualTo(200);
        return result.body();
    }

    private ChangeResult rawChange(
            String token,
            String body,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS))
                .as("consent requests should be released together")
                .isTrue();
        var response = mockMvc.perform(put("/api/v1/consents/MEAL_AI")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
        return new ChangeResult(response.getStatus(), objectMapper.readTree(response.getContentAsString()));
    }

    private UUID createUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                userId,
                "concurrent-" + userId + "@example.com"
        );
        return userId;
    }

    private String accessToken(UUID userId) {
        return "Bearer " + jwtTokenProvider.createToken(userId, "user@example.com", "USER");
    }

    private String changeBody(String key) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "granted", true,
                "consentVersion", 1,
                "textHash", "a".repeat(64),
                "expectedVersion", 0,
                "idempotencyKey", key
        ));
    }

    private record ChangeResult(int status, JsonNode body) {
    }
}
