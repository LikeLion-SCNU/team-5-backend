package org.example.naeilbank.evidence;

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
class EvidenceMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("evidence_migration_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    @Test
    void populatedV3GainsVersionMetadataWithoutChangingEvidenceOrLedgerLinks() {
        Flyway v3 = flyway("3");
        v3.clean();
        v3.migrate();
        JdbcTemplate jdbc = jdbc();
        UUID userId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)", userId, userId + "@example.com");
        jdbc.update("insert into sources (id, title, doi_url) values (?, ?, ?)",
                sourceId, "보존 출처", "https://doi.org/10.1000/preserved");
        jdbc.update("""
                insert into conversion_rules (id, habit_type, label, minutes_delta, source_id)
                values (?, 'activity', '보존 규칙', 7, ?)
                """, ruleId, sourceId);
        Long ledgerId = jdbc.queryForObject("""
                insert into ledger_entries (user_id, entry_date, habit_type, minutes_delta, rule_id)
                values (?, current_date, 'activity', 7, ?) returning id
                """, Long.class, userId, ruleId);

        flyway(null).migrate();

        assertThat(jdbc.queryForMap("""
                select title, logical_key, version_number, row_version
                from sources where id = ?
                """, sourceId)).containsEntry("title", "보존 출처")
                .containsEntry("logical_key", sourceId)
                .containsEntry("version_number", 1)
                .containsEntry("row_version", 0L);
        assertThat(jdbc.queryForMap("""
                select label, logical_key, version_number, row_version
                from conversion_rules where id = ?
                """, ruleId)).containsEntry("label", "보존 규칙")
                .containsEntry("logical_key", ruleId)
                .containsEntry("version_number", 1)
                .containsEntry("row_version", 0L);
        assertThat(jdbc.queryForObject(
                "select rule_id from ledger_entries where id = ?", UUID.class, ledgerId
        )).isEqualTo(ruleId);

        assertThatThrownBy(() -> jdbc.update("""
                insert into sources (logical_key, title, doi_url)
                values (?, 'HTTP 출처', 'http://example.com/not-allowed')
                """, UUID.randomUUID())).hasMessageContaining("sources_doi_url_https_check");
        assertThatThrownBy(() -> jdbc.update("""
                insert into conversion_rules
                    (logical_key, habit_type, label, minutes_delta, source_id)
                values (?, 'activity', '0분 규칙', 0, ?)
                """, UUID.randomUUID(), sourceId))
                .hasMessageContaining("conversion_rules_minutes_delta_nonzero_check");
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        ));
    }
}
