package org.example.naeilbank.todo6;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.cleanAndMigrate;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.flyway;

@Testcontainers
class LegacyDomainMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("legacy_domain_migration_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    @Test
    void legacyV2UrlRowsSurviveButLatestRejectsUrlOnlyOrMediaLessRows() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, "2");
        UUID userId = insertUser(jdbc, "legacy-media@example.com");
        UUID otherUserId = insertUser(jdbc, "legacy-media-other@example.com");
        UUID mealId = UUID.randomUUID();
        UUID faceId = UUID.randomUUID();
        jdbc.update("""
                insert into meal_records (id, user_id, record_date, photo_url, status)
                values (?, ?, ?, 'https://legacy.invalid/meal.jpg', 'confirmed')
                """, mealId, userId, Date.valueOf(LocalDate.of(2026, 8, 19)));
        jdbc.update("""
                insert into face_simulations (id, user_id, original_photo_url, status)
                values (?, ?, 'https://legacy.invalid/face.jpg', 'done')
                """, faceId, userId);

        flyway(POSTGRES, null).migrate();

        assertThat(jdbc.queryForObject(
                "select photo_url from meal_records where id = ?", String.class, mealId
        )).isEqualTo("https://legacy.invalid/meal.jpg");
        assertThat(jdbc.queryForObject(
                "select original_photo_url from face_simulations where id = ?", String.class, faceId
        )).isEqualTo("https://legacy.invalid/face.jpg");
        assertThatThrownBy(() -> jdbc.update(
                "update meal_records set user_id = ? where id = ?", otherUserId, mealId
        )).hasMessageContaining("meal_records user_id is immutable");
        assertThatThrownBy(() -> jdbc.update(
                "update face_simulations set user_id = ? where id = ?", otherUserId, faceId
        )).hasMessageContaining("face_simulations user_id is immutable");

        assertMealRejected(jdbc, userId, "https://new.invalid/meal.jpg");
        assertMealRejected(jdbc, userId, null);
        assertFaceRejected(jdbc, userId, "https://new.invalid/face.jpg");
        assertFaceRejected(jdbc, userId, null);
    }

    @Test
    void legacyProtectionEventIsBackfilledPreservedUniqueAndImmutable() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, "2");
        UUID userId = insertUser(jdbc, "legacy-protection@example.com");
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                insert into protection_events (id, user_id, event_type, detail_json)
                values (?, ?, 'manual_on', '{"source":"legacy"}'::jsonb)
                """, eventId, userId);

        flyway(POSTGRES, null).migrate();

        Map<String, Object> migrated = jdbc.queryForMap("""
                select event_type, detail_json::text, idempotency_key, version, updated_at
                from protection_events where id = ?
                """, eventId);
        assertThat(migrated.get("event_type")).isEqualTo("manual_on");
        assertThat(migrated.get("detail_json").toString()).contains("legacy");
        assertThat(migrated.get("idempotency_key")).asString().isNotBlank();
        assertThat(migrated.get("version")).isEqualTo(0L);
        assertThat(migrated.get("updated_at")).isNotNull();

        jdbc.update("""
                insert into protection_events
                    (user_id, event_type, detail_json, idempotency_key)
                values (?, 'suggested', '{}', 'same-protection-event')
                """, userId);
        assertThatThrownBy(() -> jdbc.update("""
                insert into protection_events
                    (user_id, event_type, detail_json, idempotency_key)
                values (?, 'suggested', '{}', 'same-protection-event')
                """, userId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update protection_events set event_type = 'manual_off' where id = ?", eventId
        )).hasMessageContaining("protection_events");
        assertThatThrownBy(() -> jdbc.update(
                "delete from protection_events where id = ?", eventId
        )).hasMessageContaining("protection_events");
    }

    private UUID insertUser(JdbcTemplate jdbc, String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)", userId, email);
        return userId;
    }

    private void assertMealRejected(JdbcTemplate jdbc, UUID userId, String photoUrl) {
        assertThatThrownBy(() -> jdbc.update("""
                insert into meal_records (user_id, record_date, photo_url, status)
                values (?, ?, ?, 'analyzing')
                """, userId, Date.valueOf(LocalDate.of(2026, 8, 20)), photoUrl))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertFaceRejected(JdbcTemplate jdbc, UUID userId, String photoUrl) {
        assertThatThrownBy(() -> jdbc.update("""
                insert into face_simulations
                    (user_id, original_photo_url, status, idempotency_key, request_hash)
                values (?, ?, 'generating', 'url-only-face', 'test-hash')
                """, userId, photoUrl))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
