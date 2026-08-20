package org.example.naeilbank.meal;

import org.example.naeilbank.domain.conversion.ConversionUnit;
import org.example.naeilbank.domain.conversion.HabitCategory;
import org.example.naeilbank.domain.meal.MealAnalysisClient;
import org.example.naeilbank.domain.meal.MealAnalysisContract;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class MealApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("meal_api_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @MockBean MealAnalysisClient mealAnalysisClient;

    @AfterEach
    void cleanRules() {
        jdbc.update("update conversion_rules set is_active = false where label like 'TEST_MEAL%'");
    }

    @Test
    void analyzeConfirmAndDuplicateConcurrentConfirmPostLedgerExactlyOnce() throws Exception {
        UUID userId = user("meal");
        grant(userId, "MEAL_AI");
        UUID mediaId = media(userId, 'd');
        rule("food", "per_serving", -4);
        when(mealAnalysisClient.analyze(eq("image/png"), any())).thenReturn(analysis("rice"));

        String created = mockMvc.perform(post("/api/v1/meals")
                        .header(HttpHeaders.AUTHORIZATION, token(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"media_blob_id":"%s","record_date":"2026-08-20"}
                                """.formatted(mediaId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending_confirm"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        UUID mealId = UUID.fromString(com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build().readTree(created).get("id").asText());

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> confirm(userId, mealId, start));
            var second = executor.submit(() -> confirm(userId, mealId, start));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(count("ledger_entries", userId)).isOne();
        assertThat(count("conversion_postings", userId)).isOne();
        mockMvc.perform(post("/api/v1/meals/{mealId}/confirm", mealId)
                        .header(HttpHeaders.AUTHORIZATION, token(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
        assertThat(count("ledger_entries", userId)).isOne();
    }

    @Test
    void wrongOwnerCannotCreateFromAnotherUsersPrivateMealMedia() throws Exception {
        UUID owner = user("meal-owner");
        UUID other = user("meal-other");
        grant(owner, "MEAL_AI");
        grant(other, "MEAL_AI");
        UUID mediaId = media(owner, 'e');

        mockMvc.perform(post("/api/v1/meals")
                        .header(HttpHeaders.AUTHORIZATION, token(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"media_blob_id":"%s","record_date":"2026-08-20"}
                                """.formatted(mediaId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
        assertThat(count("meal_records", other)).isZero();
    }

    @Test
    void failedAnalysisKeepsAnalyzingRecordAndRetryCanComplete() throws Exception {
        UUID userId = user("meal-retry");
        grant(userId, "MEAL_AI");
        UUID mediaId = media(userId, 'f');
        when(mealAnalysisClient.analyze(eq("image/png"), any()))
                .thenThrow(new AuthException(ErrorCode.MEAL_ANALYSIS_FAILED))
                .thenReturn(analysis("retry rice"));

        mockMvc.perform(post("/api/v1/meals")
                        .header(HttpHeaders.AUTHORIZATION, token(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"media_blob_id":"%s","record_date":"2026-08-20"}
                                """.formatted(mediaId)))
                .andExpect(status().isBadGateway());
        UUID mealId = jdbc.queryForObject("select id from meal_records where user_id = ?",
                UUID.class, userId);
        assertThat(mealStatus(mealId)).isEqualTo("analyzing");

        mockMvc.perform(post("/api/v1/meals/{mealId}/analyze", mealId)
                        .header(HttpHeaders.AUTHORIZATION, token(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_confirm"));
    }

    @Test
    void excludedMealNeverWritesLedger() throws Exception {
        UUID userId = user("meal-exclude");
        grant(userId, "MEAL_AI");
        UUID mediaId = media(userId, 'a');
        when(mealAnalysisClient.analyze(eq("image/png"), any())).thenReturn(analysis("rice"));
        String created = mockMvc.perform(post("/api/v1/meals")
                        .header(HttpHeaders.AUTHORIZATION, token(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"media_blob_id":"%s","record_date":"%s"}
                                """.formatted(mediaId, LocalDate.of(2026, 8, 20))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID mealId = UUID.fromString(com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build().readTree(created).get("id").asText());

        mockMvc.perform(post("/api/v1/meals/{mealId}/exclude", mealId)
                        .header(HttpHeaders.AUTHORIZATION, token(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("excluded"));
        assertThat(count("ledger_entries", userId)).isZero();
    }

    private Void confirm(UUID userId, UUID mealId, CountDownLatch start) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        mockMvc.perform(post("/api/v1/meals/{mealId}/confirm", mealId)
                        .header(HttpHeaders.AUTHORIZATION, token(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
        return null;
    }

    private MealAnalysisContract.AnalyzedMeal analysis(String foodName) {
        return new MealAnalysisContract.AnalyzedMeal(List.of(new MealAnalysisContract.AnalyzedItem(
                foodName,
                "1 serving",
                HabitCategory.FOOD,
                ConversionUnit.PER_SERVING,
                BigDecimal.ONE
        )));
    }

    private UUID user(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                id, prefix + "-" + id + "@example.com");
        return id;
    }

    private void grant(UUID userId, String purpose) {
        jdbc.update("""
                insert into consents (user_id, purpose, granted, granted_at, consent_version, text_hash, version)
                values (?, ?, true, now(), 1, ?, 0)
                """, userId, purpose, "d".repeat(64));
    }

    private UUID media(UUID userId, char hash) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into media_blobs (id, user_id, purpose, content_type, size_bytes, sha256, content)
                values (?, ?, 'meal_input', 'image/png', 4, ?, decode('89504e47', 'hex'))
                """, id, userId, String.valueOf(hash).repeat(64));
        return id;
    }

    private void rule(String habit, String unit, int minutes) {
        UUID sourceId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        jdbc.update("""
                insert into sources (id, logical_key, title, doi_url, summary_ko, scope_ko, limitations_ko)
                values (?, ?, ?, ?, 'fixture', 'fixture', 'fixture')
                """, sourceId, sourceId, "TEST_MEAL source " + ruleId,
                "https://example.test/meal/" + sourceId);
        jdbc.update("""
                insert into conversion_rules (id, logical_key, habit_type, label, condition_json,
                    minutes_delta, unit, source_id, is_active)
                values (?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, true)
                """, ruleId, ruleId, habit, "TEST_MEAL rule " + ruleId, minutes, unit, sourceId);
    }

    private int count(String table, UUID userId) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, userId);
    }

    private String mealStatus(UUID mealId) {
        return jdbc.queryForObject("select status from meal_records where id = ?", String.class, mealId);
    }

    private String token(UUID userId) {
        return "Bearer " + jwtTokenProvider.createToken(userId, "meal@example.com", "USER");
    }
}
