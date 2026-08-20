package org.example.naeilbank.conversion;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

final class ConversionIntegrationFixtures {
    private ConversionIntegrationFixtures() {
    }

    static UUID user(JdbcTemplate jdbc, String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email, auth_provider, role) values (?, ?, 'email', 'USER')",
                id, prefix + "-" + id + "@example.com");
        return id;
    }

    static UUID healthEvent(JdbcTemplate jdbc, UUID userId, LocalDate recordDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into health_daily (id, user_id, record_date, sync_status)
                values (?, ?, ?, 'synced')
                """, id, userId, recordDate);
        return id;
    }

    static UUID mealEvent(JdbcTemplate jdbc, UUID userId, LocalDate recordDate, char hashCharacter) {
        UUID mediaId = UUID.randomUUID();
        jdbc.update("""
                insert into media_blobs (id, user_id, purpose, content_type, size_bytes, sha256, content)
                values (?, ?, 'meal_input', 'image/png', 4, ?, decode('89504e47', 'hex'))
                """, mediaId, userId, String.valueOf(hashCharacter).repeat(64));
        UUID mealId = UUID.randomUUID();
        jdbc.update("""
                insert into meal_records (id, user_id, record_date, media_blob_id, status, confirmed_at)
                values (?, ?, ?, ?, 'confirmed', now())
                """, mealId, userId, recordDate, mediaId);
        UUID itemId = UUID.randomUUID();
        jdbc.update("""
                insert into meal_items (id, meal_record_id, food_name, est_minutes)
                values (?, ?, 'TEST_FIXTURE food', 0)
                """, itemId, mealId);
        return itemId;
    }
}
