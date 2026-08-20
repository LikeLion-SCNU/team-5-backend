package org.example.naeilbank.todo6;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.cleanAndMigrate;

@Testcontainers
class CompleteDomainConstraintIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("complete_constraint_contract_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    @Test
    void invalidStatusOrphanRequiredFlagAndDuplicateIdempotencyAreRejected() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, null);
        UUID userId = insertUser(jdbc, "constraint-contract@example.com");

        assertThatThrownBy(() -> jdbc.update("""
                insert into outbox_jobs (job_type, status, idempotency_key)
                values ('meal_analysis', 'not-a-status', 'invalid-status')
                """)).hasMessageContaining("outbox_jobs_status_check");
        assertThatThrownBy(() -> jdbc.update("""
                insert into notification_attempts (user_id, subscription_id, local_date, type)
                values (?, ?, ?, 'morning_statement')
                """, userId, UUID.randomUUID(), Date.valueOf(LocalDate.of(2026, 8, 20))))
                .hasMessageContaining("notification_attempts_subscription_id_fkey");
        assertThatThrownBy(() -> jdbc.update(
                "update users set protection_mode = null where id = ?", userId
        )).hasMessageContaining("protection_mode");
        assertThatThrownBy(() -> jdbc.update("""
                insert into notification_preferences (user_id, enabled, timezone, morning_time)
                values (?, null, 'Asia/Seoul', '08:00')
                """, userId)).hasMessageContaining("enabled");

    }

    @Test
    void consentPurposesAreIndependentVersionedAndAuditRowsAreImmutable() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, null);
        UUID userId = insertUser(jdbc, "consent-contract@example.com");

        for (String purpose : List.of("HEALTH_COLLECTION", "MEAL_AI", "FACE_AI", "NOTIFICATION")) {
            jdbc.update("""
                    insert into consents (user_id, purpose, granted, consent_version, text_hash)
                    values (?, ?, false, 1, ?)
                    """, userId, purpose, "sha256:" + purpose.toLowerCase());
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from consents where user_id = ?", Integer.class, userId
        )).isEqualTo(4);

        UUID auditId = jdbc.queryForObject("""
                insert into audit_events (user_id, event_type, subject_type, subject_id, detail_json)
                values (?, 'CONSENT_RECORDED', 'USER', ?, '{}'::jsonb) returning id
                """, UUID.class, userId, userId);
        assertThatThrownBy(() -> jdbc.update(
                "update audit_events set event_type = 'TAMPERED' where id = ?", auditId
        )).hasMessageContaining("audit_events is immutable");
        assertThatThrownBy(() -> jdbc.update(
                "delete from audit_events where id = ?", auditId
        )).hasMessageContaining("audit_events is immutable");
    }

    @Test
    void outboxIdempotencyIsScopedPerUserAndStillUniqueForSystemJobs() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, null);
        UUID firstUser = insertUser(jdbc, "outbox-one@example.com");
        UUID secondUser = insertUser(jdbc, "outbox-two@example.com");

        insertOutbox(jdbc, firstUser, "shared-key");
        insertOutbox(jdbc, secondUser, "shared-key");
        assertThatThrownBy(() -> insertOutbox(jdbc, firstUser, "shared-key"))
                .hasMessageContaining("outbox");

        insertOutbox(jdbc, null, "system-key");
        assertThatThrownBy(() -> insertOutbox(jdbc, null, "system-key"))
                .hasMessageContaining("outbox");
    }

    @Test
    void activeMealAndFaceRowsUseOwnedPrivateMediaWithoutUrls() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, null);
        UUID userId = insertUser(jdbc, "private-media@example.com");
        UUID otherUserId = insertUser(jdbc, "private-media-other@example.com");
        UUID mealMediaId = insertMedia(jdbc, userId, "meal_input", new byte[]{1, 2, 3});
        UUID faceMediaId = insertMedia(jdbc, userId, "face_input", new byte[]{4, 5, 6});

        UUID mealId = jdbc.queryForObject("""
                insert into meal_records (user_id, record_date, media_blob_id, status)
                values (?, ?, ?, 'analyzing') returning id
                """, UUID.class, userId, Date.valueOf(LocalDate.of(2026, 8, 20)), mealMediaId);
        UUID faceId = jdbc.queryForObject("""
                insert into face_simulations
                    (user_id, source_media_id, status, idempotency_key, request_hash)
                values (?, ?, 'generating', 'private-media-face', 'test-hash') returning id
                """, UUID.class, userId, faceMediaId);

        assertThat(jdbc.queryForObject(
                "select count(*) from meal_records where media_blob_id = ? and photo_url is null",
                Integer.class, mealMediaId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from face_simulations
                where source_media_id = ? and original_photo_url is null
                  and result_current_url is null and result_improved_url is null
                """, Integer.class, faceMediaId)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "update meal_records set user_id = ? where id = ?", otherUserId, mealId
        )).hasMessageContaining("meal_records user_id is immutable");
        assertThatThrownBy(() -> jdbc.update(
                "update face_simulations set user_id = ? where id = ?", otherUserId, faceId
        )).hasMessageContaining("face_simulations user_id is immutable");
    }

    private UUID insertUser(JdbcTemplate jdbc, String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)", userId, email);
        return userId;
    }

    private UUID insertMedia(JdbcTemplate jdbc, UUID userId, String purpose, byte[] content) {
        return jdbc.queryForObject("""
                insert into media_blobs
                    (user_id, purpose, content_type, size_bytes, sha256, content)
                values (?, ?, 'image/jpeg', ?, ?, ?) returning id
                """, UUID.class, userId, purpose, (long) content.length,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", content);
    }

    private void insertOutbox(JdbcTemplate jdbc, UUID userId, String idempotencyKey) {
        jdbc.update("""
                insert into outbox_jobs (user_id, job_type, status, idempotency_key)
                values (?, 'meal_analysis', 'pending', ?)
                """, userId, idempotencyKey);
    }
}
