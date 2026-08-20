package org.example.naeilbank.todo6;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.cleanAndMigrate;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.flyway;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.insertLedgerSeed;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.preservationDigest;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.publicTables;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.publicViews;
import static org.example.naeilbank.todo6.CompleteDomainTestSupport.triggerCount;

@Testcontainers
class CompleteDomainSchemaIntegrationTest {

    private static final Set<String> V1_TABLES = Set.of(
            "consents", "conversion_rules", "deletion_logs", "face_simulations",
            "health_daily", "ledger_entries", "meal_items", "meal_records",
            "notification_logs", "plans", "protection_events", "refresh_tokens",
            "sources", "users"
    );

    private static final Set<String> LATEST_TABLES = Set.of(
            "audit_events", "balance_view_events", "consents", "conversion_rules",
            "deletion_logs", "deletion_requests", "face_simulation_outputs", "face_simulations",
            "health_daily", "ledger_entries", "media_blobs", "meal_items", "meal_records",
            "notification_attempts", "notification_logs", "notification_preferences", "outbox_jobs",
            "plan_actions", "plan_progress", "plans", "protection_events", "protection_proposals",
            "refresh_tokens", "sources", "users", "web_push_subscriptions"
    );

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("complete_schema_contract_test")
            .withUsername("naeil")
            .withPassword("naeil_test");

    @Test
    void v2KeepsFourteenTablesTwoViewsAndAppendOnlyLedger() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, "2");

        assertThat(publicTables(jdbc)).containsExactlyElementsOf(V1_TABLES.stream().sorted().toList());
        assertThat(publicViews(jdbc)).containsExactly("v_balance", "v_daily_net");
        assertThat(triggerCount(jdbc, "ledger_entries", "trg_ledger_no_update")).isEqualTo(1);

        var seed = insertLedgerSeed(jdbc, "v2-contract@example.com");
        assertThatThrownBy(() -> jdbc.update(
                "update ledger_entries set minutes_delta = 99 where id = ?", seed.ledgerId()
        )).hasMessageContaining("ledger_entries is append-only");
    }

    @Test
    void latestMigrationCreatesTheExactCompleteModelInventory() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, null);

        assertThat(publicTables(jdbc)).containsExactlyElementsOf(LATEST_TABLES.stream().sorted().toList());
        assertThat(publicViews(jdbc)).containsExactly("v_balance", "v_daily_net");
        assertThat(triggerCount(jdbc, "ledger_entries", "trg_ledger_no_update")).isEqualTo(1);
    }

    @Test
    void populatedV2MigratesToLatestInsideItsDedicatedContainerWithoutChangingRows() {
        JdbcTemplate jdbc = cleanAndMigrate(POSTGRES, "2");
        var first = insertLedgerSeed(jdbc, "preserve-one@example.com");
        var second = insertLedgerSeed(jdbc, "preserve-two@example.com");
        List<Integer> countsBefore = List.of(
                jdbc.queryForObject("select count(*) from users", Integer.class),
                jdbc.queryForObject("select count(*) from ledger_entries", Integer.class)
        );
        String digestBefore = preservationDigest(jdbc);

        flyway(POSTGRES, null).migrate();

        assertThat(List.of(
                jdbc.queryForObject("select count(*) from users", Integer.class),
                jdbc.queryForObject("select count(*) from ledger_entries", Integer.class)
        )).isEqualTo(countsBefore);
        assertThat(preservationDigest(jdbc)).isEqualTo(digestBefore);
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_entries where id in (?, ?)",
                Integer.class, first.ledgerId(), second.ledgerId()
        )).isEqualTo(2);
    }
}
