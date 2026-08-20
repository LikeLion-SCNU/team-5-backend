package org.example.naeilbank.conversion;

import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.naeilbank.conversion.ConversionIntegrationFixtures.healthEvent;
import static org.example.naeilbank.conversion.ConversionIntegrationFixtures.mealEvent;
import static org.example.naeilbank.conversion.ConversionIntegrationFixtures.user;

@Testcontainers
@ActiveProfiles("integration-test")
@SpringBootTest
@TestMethodOrder(MethodOrderer.Random.class)
class ConversionFailureIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conversion_failure_test").withUsername("naeil").withPassword("naeil_test");

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
        jdbc.update("update conversion_rules set is_active = false where label like 'TEST_FIXTURE%'");
        assertThat(jdbc.queryForObject("""
                select count(*) from conversion_rules
                where is_active and label like 'TEST_FIXTURE%'
                """, Integer.class)).isZero();
    }

    @Test
    void unavailableInactiveConditionalAndExtremeInputsLeaveLedgerAndOutboxEmpty() {
        UUID userId = user(jdbc, "conversion-failure");
        UUID healthEvent = healthEvent(jdbc, userId, LocalDate.of(2026, 8, 20));
        UUID mealEvent = mealEvent(jdbc, userId, LocalDate.of(2026, 8, 20), 'b');
        assertError(userId, command(healthEvent, ConversionSourceType.HEALTH_DAILY,
                        HabitCategory.SLEEP, ConversionUnit.PER_HOUR, "1"),
                ErrorCode.CONVERSION_RULE_UNAVAILABLE);
        rule(HabitCategory.SLEEP, ConversionUnit.PER_MINUTE, 1, true, true, "{}");
        rule(HabitCategory.SLEEP, ConversionUnit.PER_MINUTE, 2, true, true, "{}");
        assertError(userId, command(healthEvent, ConversionSourceType.HEALTH_DAILY,
                        HabitCategory.SLEEP, ConversionUnit.PER_MINUTE, "1"),
                ErrorCode.CONVERSION_RULE_AMBIGUOUS);
        rule(HabitCategory.ACTIVITY, ConversionUnit.PER_1000_STEPS, 7, false, true, "{}");
        assertError(userId, command(healthEvent, ConversionSourceType.HEALTH_DAILY,
                        HabitCategory.ACTIVITY, ConversionUnit.PER_1000_STEPS, "1000"),
                ErrorCode.CONVERSION_RULE_UNAVAILABLE);
        rule(HabitCategory.SCREEN_TIME, ConversionUnit.PER_HOUR, -3, true, false, "{}");
        assertError(userId, command(healthEvent, ConversionSourceType.HEALTH_DAILY,
                        HabitCategory.SCREEN_TIME, ConversionUnit.PER_HOUR, "1"),
                ErrorCode.CONVERSION_RULE_UNAVAILABLE);
        rule(HabitCategory.FOOD, ConversionUnit.PER_SERVING, 525600, true, true, "{}");
        assertError(userId, command(mealEvent, ConversionSourceType.MEAL_ITEM,
                        HabitCategory.FOOD, ConversionUnit.PER_SERVING, "1000000000"),
                ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE);
        rule(HabitCategory.ALCOHOL, ConversionUnit.PER_DRINK, -5, true, true,
                "{\"minimum\":1}");
        assertError(userId, command(mealEvent, ConversionSourceType.MEAL_ITEM,
                        HabitCategory.ALCOHOL, ConversionUnit.PER_DRINK, "1"),
                ErrorCode.CONVERSION_CONDITION_UNSUPPORTED);

        assertThat(count("ledger_entries", userId)).isZero();
        assertThat(count("conversion_postings", userId)).isZero();
        assertThat(count("outbox_jobs", userId)).isZero();
    }

    @Test
    void injectedPostingFailureRollsBackLedgerAndLeavesNoOutboxMutation() {
        UUID userId = user(jdbc, "conversion-failure");
        UUID healthEvent = healthEvent(jdbc, userId, LocalDate.of(2026, 8, 20));
        rule(HabitCategory.ACTIVITY, ConversionUnit.PER_1000_STEPS, 7, true, true, "{}");
        jdbc.execute("""
                create function fail_test_conversion_posting() returns trigger language plpgsql as $$
                begin raise exception 'TEST_FIXTURE injected posting failure'; end $$
                """);
        jdbc.execute("""
                create trigger trg_test_conversion_posting_failure
                before insert on conversion_postings for each row execute function fail_test_conversion_posting()
                """);
        try {
            assertThatThrownBy(() -> service.convert(userId,
                    command(healthEvent, ConversionSourceType.HEALTH_DAILY,
                            HabitCategory.ACTIVITY, ConversionUnit.PER_1000_STEPS, "2500")))
                    .rootCause().hasMessageContaining("TEST_FIXTURE injected posting failure");
            assertThat(count("ledger_entries", userId)).isZero();
            assertThat(count("conversion_postings", userId)).isZero();
            assertThat(count("outbox_jobs", userId)).isZero();
        } finally {
            jdbc.execute("drop trigger if exists trg_test_conversion_posting_failure on conversion_postings");
            jdbc.execute("drop function if exists fail_test_conversion_posting()");
        }
    }

    @Test
    void missingAndWrongOwnerEventsAreIndistinguishableAndWriteNothing() {
        UUID userId = user(jdbc, "conversion-owner");
        UUID otherUser = user(jdbc, "conversion-other");
        UUID otherEvent = healthEvent(jdbc, otherUser, LocalDate.of(2026, 8, 20));
        ConversionCommand wrongOwner = command(otherEvent, ConversionSourceType.HEALTH_DAILY,
                HabitCategory.ACTIVITY, ConversionUnit.PER_1000_STEPS, "1000");
        ConversionCommand missing = command(UUID.randomUUID(), ConversionSourceType.HEALTH_DAILY,
                HabitCategory.ACTIVITY, ConversionUnit.PER_1000_STEPS, "1000");

        assertError(userId, wrongOwner, ErrorCode.CONVERSION_SOURCE_EVENT_NOT_FOUND);
        assertError(userId, missing, ErrorCode.CONVERSION_SOURCE_EVENT_NOT_FOUND);
        assertThat(count("ledger_entries", userId)).isZero();
        assertThat(count("conversion_postings", userId)).isZero();
        assertThat(count("outbox_jobs", userId)).isZero();
    }

    private void assertError(UUID userId, ConversionCommand command, ErrorCode expected) {
        assertThatThrownBy(() -> service.convert(userId, command))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }

    private void rule(HabitCategory category, ConversionUnit unit, int rate,
                      boolean ruleActive, boolean sourceActive, String condition) {
        UUID sourceId = UUID.randomUUID();
        jdbc.update("""
                insert into sources (id, logical_key, title, doi_url, summary_ko, scope_ko,
                    limitations_ko, is_active) values (?, ?, 'TEST_FIXTURE source', ?,
                    'fixture', 'fixture', 'fixture', ?)
                """, sourceId, sourceId, "https://example.test/fixture/" + sourceId, sourceActive);
        UUID ruleId = UUID.randomUUID();
        jdbc.update("""
                insert into conversion_rules (id, logical_key, habit_type, label, condition_json,
                    minutes_delta, unit, source_id, is_active)
                values (?, ?, ?, 'TEST_FIXTURE rule', cast(? as jsonb), ?, ?, ?, ?)
                """, ruleId, ruleId, category.persistedValue().name(), condition,
                rate, unit.persistedValue(), sourceId, ruleActive);
    }

    private ConversionCommand command(UUID eventId, ConversionSourceType sourceType,
                                      HabitCategory category, ConversionUnit unit, String value) {
        return new ConversionCommand(eventId, sourceType, category,
                unit, new BigDecimal(value), LocalDate.of(2026, 8, 20));
    }


    private int count(String table, UUID userId) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, userId);
    }
}
