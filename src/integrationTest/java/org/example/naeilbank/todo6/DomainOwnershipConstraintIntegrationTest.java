package org.example.naeilbank.todo6;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.cleanAndMigrate;

@Testcontainers
class DomainOwnershipConstraintIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("domain_ownership_contract_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    @Test
    void mealAndFaceSourcesRejectMediaOwnedByAnotherUser() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, null);
        UUID owner = insertUser(jdbc, "workflow-owner@example.com");
        UUID other = insertUser(jdbc, "media-owner@example.com");
        UUID otherMealMedia = insertMedia(jdbc, other, "meal_input", "01");
        UUID otherFaceMedia = insertMedia(jdbc, other, "face_input", "02");

        assertThatThrownBy(() -> jdbc.update("""
                insert into meal_records (user_id, record_date, media_blob_id, status)
                values (?, ?, ?, 'analyzing')
                """, owner, Date.valueOf(LocalDate.of(2026, 8, 20)), otherMealMedia))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into face_simulations (user_id, source_media_id, status)
                values (?, ?, 'generating')
                """, owner, otherFaceMedia))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void faceOutputsRejectCrossUserSimulationOrMediaReferences() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, null);
        UUID firstUser = insertUser(jdbc, "face-owner@example.com");
        UUID secondUser = insertUser(jdbc, "face-other@example.com");
        UUID firstSource = insertMedia(jdbc, firstUser, "face_input", "11");
        UUID simulationId = jdbc.queryForObject("""
                insert into face_simulations (user_id, source_media_id, status)
                values (?, ?, 'generating') returning id
                """, UUID.class, firstUser, firstSource);
        UUID firstOutput = insertMedia(jdbc, firstUser, "face_output_current", "12");
        UUID secondOutput = insertMedia(jdbc, secondUser, "face_output_current", "13");

        assertThatThrownBy(() -> insertOutput(
                jdbc, firstUser, simulationId, secondOutput, "current"
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertOutput(
                jdbc, secondUser, simulationId, firstOutput, "current"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void notificationAttemptRejectsAnotherUsersSubscription() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, null);
        UUID firstUser = insertUser(jdbc, "notify-one@example.com");
        UUID secondUser = insertUser(jdbc, "notify-two@example.com");
        UUID subscriptionId = jdbc.queryForObject("""
                insert into web_push_subscriptions
                    (user_id, endpoint_hash, endpoint_ciphertext, p256dh_ciphertext, auth_ciphertext)
                values (?, ?, 'cipher-endpoint', 'cipher-p256dh', 'cipher-auth') returning id
                """, UUID.class, secondUser,
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");

        assertThatThrownBy(() -> jdbc.update("""
                insert into notification_attempts (user_id, subscription_id, local_date, type)
                values (?, ?, ?, 'morning_statement')
                """, firstUser, subscriptionId, Date.valueOf(LocalDate.of(2026, 8, 20))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mealFaceSourceAndFaceOutputRejectWrongMediaPurposes() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, null);
        UUID userId = insertUser(jdbc, "media-purpose@example.com");
        UUID mealInput = insertMedia(jdbc, userId, "meal_input", "21");
        UUID faceInput = insertMedia(jdbc, userId, "face_input", "22");
        UUID currentOutput = insertMedia(jdbc, userId, "face_output_current", "23");
        UUID improvedOutput = insertMedia(jdbc, userId, "face_output_improved", "24");

        assertThatThrownBy(() -> jdbc.update("""
                insert into meal_records (user_id, record_date, media_blob_id, status)
                values (?, ?, ?, 'analyzing')
                """, userId, Date.valueOf(LocalDate.of(2026, 8, 20)), faceInput))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into face_simulations (user_id, source_media_id, status)
                values (?, ?, 'generating')
                """, userId, mealInput)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into face_simulations (user_id, source_media_id, status)
                values (?, ?, 'generating')
                """, userId, currentOutput)).isInstanceOf(DataIntegrityViolationException.class);

        UUID simulationId = jdbc.queryForObject("""
                insert into face_simulations (user_id, source_media_id, status)
                values (?, ?, 'generating') returning id
                """, UUID.class, userId, faceInput);
        assertThatThrownBy(() -> insertOutput(
                jdbc, userId, simulationId, improvedOutput, "current"
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertOutput(
                jdbc, userId, simulationId, currentOutput, "improved"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertUser(JdbcTemplate jdbc, String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)", userId, email);
        return userId;
    }

    private UUID insertMedia(JdbcTemplate jdbc, UUID userId, String purpose, String shaSuffix) {
        byte[] content = new byte[]{1, 2, 3};
        String sha = (shaSuffix + "0".repeat(64)).substring(0, 64);
        return jdbc.queryForObject("""
                insert into media_blobs
                    (user_id, purpose, content_type, size_bytes, sha256, content)
                values (?, ?, 'image/jpeg', ?, ?, ?) returning id
                """, UUID.class, userId, purpose, (long) content.length, sha, content);
    }

    private void insertOutput(
            JdbcTemplate jdbc,
            UUID userId,
            UUID simulationId,
            UUID mediaId,
            String label
    ) {
        jdbc.update("""
                insert into face_simulation_outputs
                    (user_id, simulation_id, media_blob_id, label, model_version, prompt_version)
                values (?, ?, ?, ?, 'model-v1', 'prompt-v1')
                """, userId, simulationId, mediaId, label);
    }
}
