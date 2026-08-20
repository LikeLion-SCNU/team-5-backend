package org.example.naeilbank.todo6;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.assertExactColumns;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.assertForeignKey;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.cleanAndMigrate;

@Testcontainers
class CompleteDomainMetadataIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("complete_metadata_contract_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    @Test
    void newTablesExposeExactOwnershipLifecycleVersionAndIdempotencyColumns() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, null);

        assertExactColumns(jdbc, "audit_events", Set.of(
                "id", "user_id", "event_type", "subject_type", "subject_id", "detail_json", "created_at"));
        assertExactColumns(jdbc, "media_blobs", Set.of(
                "id", "user_id", "purpose", "status", "content_type", "size_bytes", "sha256", "content",
                "version", "created_at", "updated_at", "deleted_at"));
        assertExactColumns(jdbc, "web_push_subscriptions", Set.of(
                "id", "user_id", "endpoint_hash", "endpoint_ciphertext", "p256dh_ciphertext", "auth_ciphertext",
                "expiration_time", "active", "version", "created_at", "updated_at"));
        assertExactColumns(jdbc, "notification_preferences", Set.of(
                "user_id", "enabled", "timezone", "morning_time", "version", "created_at", "updated_at"));
        assertExactColumns(jdbc, "notification_attempts", Set.of(
                "id", "user_id", "subscription_id", "local_date", "type", "status", "attempt_count",
                "next_attempt_at", "version", "created_at", "updated_at"));
        assertExactColumns(jdbc, "outbox_jobs", Set.of(
                "id", "user_id", "job_type", "status", "idempotency_key", "payload_json", "attempt_count",
                "next_attempt_at", "version", "created_at", "updated_at"));
        assertExactColumns(jdbc, "plan_actions", Set.of(
                "id", "plan_id", "position", "action_type", "target_minutes", "source_id", "rule_id",
                "version", "created_at", "updated_at"));
        assertExactColumns(jdbc, "plan_progress", Set.of(
                "id", "plan_id", "progress_date", "completed_minutes", "version", "created_at", "updated_at"));
        assertExactColumns(jdbc, "protection_proposals", Set.of(
                "id", "user_id", "status", "idempotency_key", "version", "created_at", "responded_at",
                "updated_at"));
        assertExactColumns(jdbc, "balance_view_events", Set.of(
                "id", "user_id", "balance_minutes", "idempotency_key", "created_at"));
        assertExactColumns(jdbc, "face_simulation_outputs", Set.of(
                "id", "user_id", "simulation_id", "media_blob_id", "label", "model_version", "prompt_version",
                "created_at"));
        assertExactColumns(jdbc, "deletion_requests", Set.of(
                "id", "user_id", "scope", "status", "idempotency_key", "attempt_count", "requested_at",
                "completed_at", "version", "updated_at"));
    }

    @Test
    void legacyTablesGainOnlyTheRequiredVersionAndPrivateMediaLinks() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, null);

        assertExactColumns(jdbc, "consents", Set.of(
                "id", "user_id", "purpose", "granted", "granted_at", "revoked_at",
                "consent_version", "text_hash", "version", "updated_at"));
        assertExactColumns(jdbc, "meal_records", Set.of(
                "id", "user_id", "record_date", "photo_url", "media_blob_id", "status",
                "confirmed_at", "created_at"));
        assertExactColumns(jdbc, "face_simulations", Set.of(
                "id", "user_id", "original_photo_url", "result_current_url", "result_improved_url",
                "trend_desc", "status", "created_at", "source_media_id", "version", "updated_at",
                "idempotency_key", "request_hash", "failure_reason", "processing_started_at",
                "next_attempt_at", "claim_token", "attempt_count", "completed_at", "cancelled_at"));
        assertExactColumns(jdbc, "protection_events", Set.of(
                "id", "user_id", "event_type", "detail_json", "created_at", "idempotency_key", "version",
                "updated_at"));

        assertForeignKey(jdbc, "meal_records", "media_blob_id", "media_blobs", "id");
        assertForeignKey(jdbc, "face_simulations", "source_media_id", "media_blobs", "id");
        assertForeignKey(jdbc, "face_simulation_outputs", "user_id", "users", "id");
        assertForeignKey(jdbc, "face_simulation_outputs", "media_blob_id", "media_blobs", "id");
        assertForeignKey(jdbc, "face_simulation_outputs", "simulation_id", "face_simulations", "id");
        assertForeignKey(jdbc, "protection_events", "user_id", "users", "id");
    }

    @Test
    void privateMediaIsByteaAndActiveModelAddsNoUrlColumns() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, null);

        Map<String, String> types = jdbc.query("""
                select column_name, data_type from information_schema.columns
                where table_schema = 'public' and table_name = 'media_blobs'
                """, resultSet -> {
            var result = new java.util.HashMap<String, String>();
            while (resultSet.next()) {
                result.put(resultSet.getString("column_name"), resultSet.getString("data_type"));
            }
            return result;
        });
        assertThat(types).containsEntry("content", "bytea").containsEntry("size_bytes", "bigint");

        List<String> activeUrlColumns = jdbc.queryForList("""
                select table_name || '.' || column_name
                from information_schema.columns
                where table_schema = 'public'
                  and table_name in ('media_blobs', 'face_simulation_outputs')
                  and (column_name = 'url' or column_name like '%\\_url' escape '\\')
                order by table_name, column_name
                """, String.class);
        assertThat(activeUrlColumns).isEmpty();

        List<String> nullableLegacyUrls = jdbc.queryForList("""
                select table_name || '.' || column_name
                from information_schema.columns
                where table_schema = 'public'
                  and ((table_name = 'meal_records' and column_name = 'photo_url')
                    or (table_name = 'face_simulations' and column_name in
                        ('original_photo_url', 'result_current_url', 'result_improved_url')))
                  and is_nullable = 'YES'
                order by table_name, column_name
                """, String.class);
        assertThat(nullableLegacyUrls).containsExactly(
                "face_simulations.original_photo_url",
                "face_simulations.result_current_url",
                "face_simulations.result_improved_url",
                "meal_records.photo_url"
        );
    }
}
