package org.example.naeilbank.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionReceipt;
import org.example.naeilbank.domain.conversion.ConversionService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

abstract class LedgerIntegrationSupport {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_test").withUsername("naeil").withPassword("naeil_test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ConversionService conversionService;

    @BeforeEach
    void cleanLedgerFixtures() {
        jdbc.execute("truncate table users, sources restart identity cascade");
    }

    UsernamePasswordAuthenticationToken auth(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(userId.toString(), "n/a", List.of());
    }

    UUID user(String prefix, boolean protectionMode) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, email, auth_provider, role, protection_mode)
                values (?, ?, 'email', 'USER', ?)
                """, id, prefix + "-" + id + "@example.com", protectionMode);
        jdbc.update("insert into notification_preferences (user_id, timezone) values (?, ?)",
                id, "Asia/Seoul");
        return id;
    }

    UUID rule() {
        return rule("sleep", 1, "per_unit");
    }

    UUID rule(String habitType, int minutes, String unit) {
        UUID source = UUID.randomUUID();
        jdbc.update("""
                insert into sources (id, logical_key, title, doi_url, summary_ko, scope_ko,
                    limitations_ko) values (?, ?, ?, ?, 'fixture', 'fixture', 'fixture')
                """, source, source, "TEST_FIXTURE ledger " + source,
                "https://example.test/ledger/" + source);
        UUID rule = UUID.randomUUID();
        jdbc.update("""
                insert into conversion_rules (id, logical_key, habit_type, label, condition_json,
                    minutes_delta, unit, source_id, is_active)
                values (?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, true)
                """, rule, rule, habitType, "TEST_FIXTURE ledger " + rule, minutes, unit, source);
        return rule;
    }

    long entry(UUID user, LocalDate date, int minutes, UUID rule, String refType) {
        return jdbc.queryForObject("""
                insert into ledger_entries (user_id, entry_date, habit_type, minutes_delta,
                    rule_id, ref_type, ref_id, created_at)
                values (?, ?, 'sleep', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, user, date, minutes, rule, refType, UUID.randomUUID(),
                date.atTime(3, 0).atOffset(ZoneOffset.UTC));
    }

    UUID healthEvent(UUID user, LocalDate date) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into health_daily (id, user_id, record_date, sync_status)
                values (?, ?, ?, 'synced')
                """, id, user, date);
        return id;
    }

    long digest(UUID user) {
        return jdbc.queryForObject("""
                select count(*) * 1000000 + coalesce(sum(minutes_delta), 0)
                from ledger_entries where user_id = ?
                """, Long.class, user);
    }

    void bulkEntries(UUID user, UUID other, UUID rule, int count) {
        jdbc.update("""
                insert into ledger_entries (user_id, entry_date, habit_type, minutes_delta,
                    rule_id, ref_type, ref_id, created_at)
                select case when series % 2 = 0 then ? else ? end,
                       date '2026-01-01' + (series % 90)::int,
                       'sleep', 1, ?, 'health_daily', ?,
                       timestamp with time zone '2026-01-01T00:00:00Z'
                           + series * interval '1 second'
                from generate_series(1, ?) as series
                """, user, other, rule, UUID.randomUUID(), count);
    }

    record ConversionOutcome(ConversionReceipt receipt, Throwable error) {
    }

    record RequestOutcome(int status, String body, Throwable error) {
    }

    record WriteOutcome(long entryId, Throwable error) {
    }
}
