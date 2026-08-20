package org.example.naeilbank.domain.conversion;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalRuleSeedMigrationTest {
    private static final String MIGRATION = "/db/migration/V8__seed_canonical_conversion_rules.sql";

    @Test
    void v8PinsFiveCandidateCoefficientsUnitsAndEvidenceClassifications() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("('activity', 3, 'per_minute', 'DERIVED',")
                .contains("('screen_time', -22, 'per_hour', 'MEASURED',")
                .contains("('sleep', -36, 'per_unit', 'DERIVED',")
                .contains("('alcohol', -15, 'per_drink', 'DERIVED',")
                .contains("('food', 18, 'per_serving', 'DERIVED',");
    }

    @Test
    void v8PreservesSafetyDisclosuresAndNeverCreatesPositiveAlcoholCredit() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("illustrative, population-level association")
                .contains("not an individual lifespan prediction or medical advice")
                .contains("short-sleep category RR 1.12")
                .contains("one short-sleep day below seven hours")
                .contains("+60/20=+3")
                .contains("drinks above the first")
                .contains("no positive alcohol credit")
                .contains("five qualifying servings per day")
                .contains("24 minutes was rejected as a male-specific overgeneralization");
        assertThat(sql)
                .doesNotContain("('sleep', -60")
                .doesNotContain("('alcohol', -30, 'per_drink'")
                .doesNotContain("('alcohol', 15, 'per_drink'");
    }

    @Test
    void v8AddsBoundedOptionalModerateActivityInput() throws IOException {
        assertThat(migrationSql())
                .contains("ADD COLUMN moderate_activity_minutes integer")
                .contains("CHECK (moderate_activity_minutes BETWEEN 0 AND 1440) NOT VALID");
    }

    @Test
    void v8UsesStableVersionedLineageAndIdempotentConflictDetection() throws IOException {
        String sql = migrationSql();

        assertThat(sql)
                .contains("version_number")
                .contains("logical_key")
                .contains("ON CONFLICT (logical_key, version_number) DO NOTHING")
                .contains("CANONICAL_SOURCE_CONFLICT")
                .contains("CANONICAL_RULE_CONFLICT");
    }

    private String migrationSql() throws IOException {
        try (var input = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(input).as("canonical V8 migration").isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
