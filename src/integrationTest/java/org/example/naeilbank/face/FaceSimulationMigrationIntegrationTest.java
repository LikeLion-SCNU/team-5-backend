package org.example.naeilbank.face;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FaceSimulationMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("naeil_bank_face_migration")
            .withUsername("naeil")
            .withPassword("naeil_test");

    @Test
    void populatedV6MigratesToRetryableV7WithoutLosingLegacySimulation() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("6")
                .load()
                .migrate();
        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        UUID simulationId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                    insert into users (id, email, auth_provider, role)
                    values ('%s', 'face-migration@example.com', 'email', 'USER')
                    """.formatted(userId));
            connection.createStatement().execute("""
                    insert into media_blobs
                        (id, user_id, purpose, content_type, size_bytes, sha256, content)
                    values ('%s', '%s', 'face_input', 'image/png', 1, '%s', decode('00', 'hex'))
                    """.formatted(mediaId, userId, "a".repeat(64)));
            connection.createStatement().execute("""
                    insert into face_simulations
                        (id, user_id, trend_desc, status, source_media_id)
                    values ('%s', '%s', 'legacy trend', 'generating', '%s')
                    """.formatted(simulationId, userId, mediaId));
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = connection();
             ResultSet row = connection.createStatement().executeQuery("""
                     select status, idempotency_key, request_hash, attempt_count,
                            next_attempt_at is not null as has_next_attempt,
                            claim_token is null as has_no_claim
                     from face_simulations
                     where id = '%s'
                     """.formatted(simulationId))) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("status")).isEqualTo("generating");
            assertThat(row.getString("idempotency_key")).isEqualTo("legacy:" + simulationId);
            assertThat(row.getString("request_hash")).isEqualTo("legacy:" + simulationId);
            assertThat(row.getInt("attempt_count")).isZero();
            assertThat(row.getBoolean("has_next_attempt")).isTrue();
            assertThat(row.getBoolean("has_no_claim")).isTrue();
        }
        try (Connection connection = connection();
             ResultSet version = connection.createStatement().executeQuery(
                     "select version from flyway_schema_history where success order by installed_rank desc limit 1")) {
            assertThat(version.next()).isTrue();
            assertThat(version.getString(1)).isEqualTo("7");
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }
}
