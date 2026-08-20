package org.example.naeilbank.health;

import org.example.naeilbank.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class HealthApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("health_api_test")
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

    @AfterEach
    void cleanRules() {
        jdbc.update("update conversion_rules set is_active = false where label like 'TEST_HEALTH%'");
    }

    @Test
    void supportedInputsAreUserDateIdempotentAndLedgerPostsReplayOnce() throws Exception {
        UUID userId = user("health");
        grant(userId, "HEALTH_COLLECTION");
        rule("sleep", "per_unit", -36);
        rule("activity", "per_1000_steps", 30);
        rule("screen_time", "per_hour", -22);

        upsert(userId, """
                {"record_date":"2026-08-20","sleep_minutes":360,"steps":2500,"screen_minutes":90,
                 "screen_metric":"sedentary_tv_equivalent"}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.sync_status").value("synced"))
                .andExpect(jsonPath("$.screen_metric").value("sedentary_tv_equivalent"))
                .andExpect(jsonPath("$.conversions.length()").value(3));
        upsert(userId, """
                {"record_date":"2026-08-20","sleep_minutes":360,"steps":2500,"screen_minutes":90,
                 "screen_metric":"sedentary_tv_equivalent"}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.conversions[0].replayed").value(true));
        upsert(userId, """
                {"record_date":"2026-08-20","steps":2500}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.sync_status").value("synced"));
        upsert(userId, """
                {"record_date":"2026-08-20","steps":3000}
                """).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HEALTH_DATA_CONFLICT"));

        assertThat(count("health_daily", userId)).isOne();
        assertThat(jdbc.queryForObject("select steps from health_daily where user_id = ?",
                Integer.class, userId)).isEqualTo(2500);
        assertThat(count("ledger_entries", userId)).isEqualTo(3);
        assertThat(count("conversion_postings", userId)).isEqualTo(3);
        assertThat(postedInput(userId, "sleep")).isEqualTo("per_unit:1.000000000000");
        assertThat(postedInput(userId, "activity")).isEqualTo("per_1000_steps:2000.000000000000");
        assertThat(postedInput(userId, "screen_time")).isEqualTo("per_hour:1.500000000000");
    }

    @Test
    void genericScreenMinutesWithoutTvEquivalentMetricFailClosed() throws Exception {
        UUID userId = user("health-generic-screen");
        grant(userId, "HEALTH_COLLECTION");

        upsert(userId, """
                {"record_date":"2026-08-23","screen_minutes":90}
                """).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HEALTH_DATA"));

        assertThat(count("health_daily", userId)).isZero();
        assertThat(count("conversion_postings", userId)).isZero();
    }

    @Test
    void conversionFailureRollsBackHealthAndLedgerWrites() throws Exception {
        UUID userId = user("health-rollback");
        grant(userId, "HEALTH_COLLECTION");
        rule("activity", "per_1000_steps", 7);
        jdbc.execute("""
                create function fail_health_posting() returns trigger language plpgsql as $$
                begin raise exception 'TEST_HEALTH posting failure'; end $$
                """);
        jdbc.execute("""
                create trigger trg_fail_health_posting
                before insert on conversion_postings for each row execute function fail_health_posting()
                """);
        try {
            upsert(userId, """
                    {"record_date":"2026-08-21","steps":1000}
                    """).andExpect(status().isInternalServerError());
            assertThat(count("health_daily", userId)).isZero();
            assertThat(count("ledger_entries", userId)).isZero();
            assertThat(count("conversion_postings", userId)).isZero();
        } finally {
            jdbc.execute("drop trigger if exists trg_fail_health_posting on conversion_postings");
            jdbc.execute("drop function if exists fail_health_posting()");
        }
    }

    @Test
    void crossUserSameDateDoesNotReadOrMutateOtherUserRow() throws Exception {
        UUID owner = user("health-owner");
        UUID other = user("health-other");
        grant(owner, "HEALTH_COLLECTION");
        grant(other, "HEALTH_COLLECTION");
        rule("activity", "per_1000_steps", 7);

        upsert(owner, """
                {"record_date":"2026-08-22","steps":1000}
                """).andExpect(status().isOk());
        upsert(other, """
                {"record_date":"2026-08-22","steps":2000}
                """).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("""
                select steps from health_daily where user_id = ? and record_date = date '2026-08-22'
                """, Integer.class, owner)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("""
                select steps from health_daily where user_id = ? and record_date = date '2026-08-22'
                """, Integer.class, other)).isEqualTo(2000);
    }

    private org.springframework.test.web.servlet.ResultActions upsert(UUID userId, String json) throws Exception {
        return mockMvc.perform(put("/api/v1/health/daily")
                .header(HttpHeaders.AUTHORIZATION, token(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
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
                """, userId, purpose, "c".repeat(64));
    }

    private void rule(String habit, String unit, int minutes) {
        UUID sourceId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        jdbc.update("""
                insert into sources (id, logical_key, title, doi_url, summary_ko, scope_ko, limitations_ko)
                values (?, ?, ?, ?, 'fixture', 'fixture', 'fixture')
                """, sourceId, sourceId, "TEST_HEALTH source " + ruleId,
                "https://example.test/health/" + sourceId);
        jdbc.update("""
                insert into conversion_rules (id, logical_key, habit_type, label, condition_json,
                    minutes_delta, unit, source_id, is_active)
                values (?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, true)
                """, ruleId, ruleId, habit, "TEST_HEALTH rule " + ruleId, minutes, unit, sourceId);
    }

    private int count(String table, UUID userId) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, userId);
    }

    private String postedInput(UUID userId, String habit) {
        return jdbc.queryForObject("""
                select input_unit || ':' || input_value::text
                from conversion_postings where user_id = ? and habit_type = ?
                """, String.class, userId, habit);
    }

    private String token(UUID userId) {
        return "Bearer " + jwtTokenProvider.createToken(userId, "health@example.com", "USER");
    }
}
