package org.example.naeilbank.ledger;

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
class KstTimezoneMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("kst_migration_test")
            .withUsername("naeil").withPassword("naeil_test");

    @Test
    void v6NormalizesLegacyTimezoneAndRejectsNonKstWrites() {
        flyway("5").clean();
        flyway("5").migrate();
        JdbcTemplate jdbc = jdbc();
        UUID legacyUser = user(jdbc, "legacy");
        jdbc.update("""
                insert into notification_preferences (user_id, timezone, morning_time)
                values (?, 'America/Los_Angeles', '09:15')
                """, legacyUser);

        flyway(null).migrate();

        assertThat(jdbc.queryForMap("""
                select timezone, morning_time from notification_preferences where user_id = ?
                """, legacyUser)).containsEntry("timezone", "Asia/Seoul")
                .hasEntrySatisfying("morning_time",
                        value -> assertThat(value.toString()).isEqualTo("09:15:00"));
        assertThatThrownBy(() -> jdbc.update("""
                update notification_preferences set timezone = 'UTC' where user_id = ?
                """, legacyUser)).hasMessageContaining("notification_preferences_timezone_check");

        UUID defaultUser = user(jdbc, "default");
        jdbc.update("insert into notification_preferences (user_id) values (?)", defaultUser);
        assertThat(jdbc.queryForMap("""
                select timezone, morning_time from notification_preferences where user_id = ?
                """, defaultUser)).containsEntry("timezone", "Asia/Seoul")
                .hasEntrySatisfying("morning_time",
                        value -> assertThat(value.toString()).isEqualTo("08:00:00"));
    }

    private UUID user(JdbcTemplate jdbc, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)",
                id, prefix + "-" + id + "@example.com");
        return id;
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
