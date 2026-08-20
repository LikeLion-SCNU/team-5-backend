package org.example.naeilbank.conversion;

import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionReceipt;
import org.example.naeilbank.domain.conversion.ConversionService;
import org.example.naeilbank.domain.conversion.ConversionSourceType;
import org.example.naeilbank.domain.conversion.ConversionUnit;
import org.example.naeilbank.domain.conversion.HabitCategory;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.naeilbank.conversion.ConversionIntegrationFixtures.healthEvent;
import static org.example.naeilbank.conversion.ConversionIntegrationFixtures.mealEvent;
import static org.example.naeilbank.conversion.ConversionIntegrationFixtures.user;

@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest
@TestMethodOrder(MethodOrderer.Random.class)
class ConversionPersistenceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conversion_test").withUsername("naeil").withPassword("naeil_test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ConversionService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void deactivateFixtureRules() {
        jdbc.update("update conversion_rules set is_active = false where label like 'TEST_FIXTURE%' or logical_key::text like '21000000-%'");
        assertThat(jdbc.queryForObject("""
                select count(*) from conversion_rules
                where is_active and label like 'TEST_FIXTURE%'
                """, Integer.class)).isZero();
    }

    @Test
    void everyLabeledFixtureCategoryPersistsExactSecondsAndImmutableLineage() {
        UUID userId = user(jdbc, "conversion");
        List<Fixture> fixtures = List.of(
                new Fixture(HabitCategory.SLEEP, ConversionUnit.PER_HOUR, "1.5", 2, 180, 3),
                new Fixture(HabitCategory.ACTIVITY, ConversionUnit.PER_1000_STEPS, "2500", 7, 1050, 18),
                new Fixture(HabitCategory.SCREEN_TIME, ConversionUnit.PER_HOUR, "2", -3, -360, -6),
                new Fixture(HabitCategory.FOOD, ConversionUnit.PER_SERVING, "2", -4, -480, -8),
                new Fixture(HabitCategory.ALCOHOL, ConversionUnit.PER_DRINK, "1", -5, -300, -5));
        UUID healthEvent = healthEvent(jdbc, userId, LocalDate.of(2026, 8, 20));
        UUID mealEvent = mealEvent(jdbc, userId, LocalDate.of(2026, 8, 20), 'a');

        for (Fixture fixture : fixtures) {
            UUID ruleId = rule(fixture, true, true);
            boolean meal = fixture.category() == HabitCategory.FOOD
                    || fixture.category() == HabitCategory.ALCOHOL;
            ConversionReceipt receipt = service.convert(userId, command(fixture,
                    meal ? mealEvent : healthEvent,
                    meal ? ConversionSourceType.MEAL_ITEM : ConversionSourceType.HEALTH_DAILY));
            assertThat(receipt.ruleId()).isEqualTo(ruleId);
            assertThat(receipt.postedSeconds()).isEqualTo(fixture.seconds());
            assertThat(receipt.ledgerMinutes()).isEqualTo(fixture.ledgerMinutes());
            assertThat(jdbc.queryForMap("""
                    select p.posted_seconds, p.rule_snapshot_json::text rule_json,
                           p.source_snapshot_json::text source_json,
                           p.input_snapshot_json::text input_json,
                           p.result_snapshot_json::text result_json, l.minutes_delta
                    from conversion_postings p join ledger_entries l on l.id = p.ledger_entry_id
                    where p.ledger_entry_id = ?
                    """, receipt.ledgerEntryId()))
                    .containsEntry("posted_seconds", fixture.seconds())
                    .containsEntry("minutes_delta", fixture.ledgerMinutes());
        }
        assertThat(count("conversion_postings", userId)).isEqualTo(5);
        assertThat(count("ledger_entries", userId)).isEqualTo(5);
        assertThatThrownBy(() -> jdbc.update("update conversion_postings set posted_seconds = 0 where user_id = ?",
                userId)).hasMessageContaining("conversion_postings is append-only");
    }

    @Test
    void replayAndConcurrentSameEventCreateOneLedgerWhileChangedReplayConflicts() throws Exception {
        UUID userId = user(jdbc, "conversion");
        Fixture fixture = new Fixture(HabitCategory.ACTIVITY, ConversionUnit.PER_1000_STEPS,
                "2500", 7, 1050, 18);
        rule(fixture, true, true);
        UUID eventId = healthEvent(jdbc, userId, LocalDate.of(2026, 8, 20));
        ConversionCommand command = command(fixture, eventId, ConversionSourceType.HEALTH_DAILY);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); return service.convert(userId, command); });
            var second = executor.submit(() -> { await(start); return service.convert(userId, command); });
            start.countDown();
            List<ConversionReceipt> receipts = List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertThat(receipts).extracting(ConversionReceipt::ledgerEntryId).containsOnly(receipts.get(0).ledgerEntryId());
            assertThat(receipts).extracting(ConversionReceipt::replayed).containsExactlyInAnyOrder(false, true);
        }
        assertThat(count("conversion_postings", userId)).isOne();
        assertThat(count("ledger_entries", userId)).isOne();

        ConversionCommand changed = new ConversionCommand(eventId, command.sourceType(), command.category(),
                command.unit(), new BigDecimal("3000"), command.entryDate());
        assertThatThrownBy(() -> service.convert(userId, changed))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void newlyActivatedVersionAffectsOnlyFutureCalculationsAndOldSnapshotStaysStable() {
        UUID userId = user(jdbc, "conversion");
        UUID sourceId = UUID.randomUUID();
        UUID logicalKey = UUID.randomUUID();
        jdbc.update("""
                insert into sources (id, logical_key, title, doi_url, summary_ko, scope_ko,
                    limitations_ko) values (?, ?, 'TEST_FIXTURE version source', ?,
                    'fixture', 'fixture', 'fixture')
                """, sourceId, sourceId, "https://example.test/fixture/" + sourceId);
        UUID v1 = versionRule(sourceId, logicalKey, 1, 7, true);
        Fixture fixture = new Fixture(HabitCategory.ACTIVITY, ConversionUnit.PER_1000_STEPS,
                "2500", 7, 1050, 18);
        UUID healthEvent = healthEvent(jdbc, userId, LocalDate.of(2026, 8, 20));
        ConversionReceipt first = service.convert(userId,
                command(fixture, healthEvent, ConversionSourceType.HEALTH_DAILY));

        jdbc.update("update conversion_rules set is_active = false where id = ?", v1);
        UUID v2 = versionRule(sourceId, logicalKey, 2, 8, true);
        LocalDate nextDate = LocalDate.of(2026, 8, 21);
        UUID nextHealthEvent = healthEvent(jdbc, userId, nextDate);
        ConversionReceipt second = service.convert(userId,
                command(fixture, nextHealthEvent, ConversionSourceType.HEALTH_DAILY, nextDate));

        assertThat(first.ruleId()).isEqualTo(v1);
        assertThat(first.postedSeconds()).isEqualTo(1050);
        assertThat(second.ruleId()).isEqualTo(v2);
        assertThat(second.postedSeconds()).isEqualTo(1200);
        String firstSnapshot = jdbc.queryForObject("""
                select rule_snapshot_json::text from conversion_postings where ledger_entry_id = ?
                """, String.class, first.ledgerEntryId());
        assertThat(firstSnapshot).contains(v1.toString(), "\"version\": 1", "\"minutesDelta\": 7");
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting concurrent conversion", exception);
        }
    }

    private UUID rule(Fixture fixture, boolean ruleActive, boolean sourceActive) {
        UUID sourceId = UUID.randomUUID();
        jdbc.update("""
                insert into sources (id, logical_key, title, doi_url, summary_ko, scope_ko,
                    limitations_ko, is_active) values (?, ?, ?, ?, 'fixture', 'fixture', 'fixture', ?)
                """, sourceId, sourceId, "TEST_FIXTURE " + fixture.category(),
                "https://example.test/fixture/" + sourceId, sourceActive);
        UUID ruleId = UUID.randomUUID();
        jdbc.update("""
                insert into conversion_rules (id, logical_key, habit_type, label, condition_json,
                    minutes_delta, unit, source_id, is_active)
                values (?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, ?)
                """, ruleId, ruleId, fixture.category().persistedValue().name(),
                "TEST_FIXTURE " + fixture.category(), fixture.rate(),
                fixture.unit().persistedValue(), sourceId, ruleActive);
        return ruleId;
    }

    private UUID versionRule(UUID sourceId, UUID logicalKey, int version, int rate, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into conversion_rules (id, logical_key, version_number, habit_type, label,
                    condition_json, minutes_delta, unit, source_id, is_active)
                values (?, ?, ?, 'activity', 'TEST_FIXTURE version rule', '{}'::jsonb,
                    ?, 'per_1000_steps', ?, ?)
                """, id, logicalKey, version, rate, sourceId, active);
        return id;
    }

    private ConversionCommand command(Fixture fixture, UUID eventId, ConversionSourceType sourceType) {
        return command(fixture, eventId, sourceType, LocalDate.of(2026, 8, 20));
    }

    private ConversionCommand command(Fixture fixture, UUID eventId, ConversionSourceType sourceType,
                                      LocalDate entryDate) {
        return new ConversionCommand(eventId, sourceType, fixture.category(),
                fixture.unit(), new BigDecimal(fixture.value()), entryDate);
    }


    private int count(String table, UUID userId) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, userId);
    }

    private record Fixture(HabitCategory category, ConversionUnit unit, String value,
                           int rate, long seconds, int ledgerMinutes) {
    }
}
