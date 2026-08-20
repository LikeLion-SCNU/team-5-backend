package org.example.naeilbank.conversion;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ConversionMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conversion_migration_test")
            .withUsername("naeil").withPassword("naeil_test");

    @Test
    void v5AddsEmptyLineageSurfaceWithoutChangingExistingLedgerEntries() {
        flyway("4").clean();
        flyway("4").migrate();
        JdbcTemplate jdbc = jdbc();
        UUID userId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                userId, "conversion-migration-" + userId + "@example.com");
        jdbc.update("""
                insert into sources (id, logical_key, title, doi_url, summary_ko, scope_ko, limitations_ko)
                values (?, ?, 'TEST_FIXTURE source', ?, 'fixture', 'fixture', 'fixture')
                """, sourceId, sourceId, "https://example.test/fixture/" + sourceId);
        jdbc.update("""
                insert into conversion_rules (id, logical_key, habit_type, label, condition_json,
                    minutes_delta, unit, source_id) values (?, ?, 'activity', 'TEST_FIXTURE rule',
                    '{}'::jsonb, 7, 'per_1000_steps', ?)
                """, ruleId, ruleId, sourceId);
        Long ledgerId = jdbc.queryForObject("""
                insert into ledger_entries (user_id, entry_date, habit_type, minutes_delta, rule_id,
                    ref_type, ref_id) values (?, current_date, 'activity', 7, ?, 'health_daily', ?)
                returning id
                """, Long.class, userId, ruleId, UUID.randomUUID());

        flyway(null).migrate();

        assertThat(jdbc.queryForMap("""
                select user_id, habit_type, minutes_delta, rule_id from ledger_entries where id = ?
                """, ledgerId)).containsEntry("user_id", userId)
                .containsEntry("habit_type", "activity")
                .containsEntry("minutes_delta", 7)
                .containsEntry("rule_id", ruleId);
        assertThat(jdbc.queryForObject("select count(*) from conversion_postings", Integer.class)).isZero();

        UUID otherUser = UUID.randomUUID();
        jdbc.update("insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                otherUser, "conversion-other-" + otherUser + "@example.com");
        assertThatThrownBy(() -> jdbc.update("""
                insert into conversion_postings
                    (user_id, source_event_id, source_event_type, entry_date, habit_type,
                     input_value, input_unit, posted_seconds, ledger_minutes_delta, rule_id,
                     source_id, ledger_entry_id, request_hash, rule_snapshot_json,
                     source_snapshot_json, input_snapshot_json, result_snapshot_json)
                values (?, ?, 'health_daily', current_date, 'activity', 1000,
                    'per_1000_steps', 420, 7, ?, ?, ?, ?, '{}'::jsonb,
                    '{}'::jsonb, '{}'::jsonb, '{}'::jsonb)
                """, otherUser, UUID.randomUUID(), ruleId, sourceId, ledgerId, "c".repeat(64)))
                .hasMessageContaining("conversion_postings_user_ledger_fkey");
        assertThat(jdbc.queryForObject("select count(*) from conversion_postings", Integer.class)).isZero();
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).cleanDisabled(false);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}
