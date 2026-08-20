package org.example.naeilbank.domain.conversion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionInput;
import org.example.naeilbank.domain.conversion.ConversionModels.RuleTerms;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class CanonicalDevGoldenVectorTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String VECTOR_PATH_ENV = "CANONICAL_DEV_VECTOR_PATH";
    private static final String REQUIRE_ENV = "REQUIRE_CANONICAL_DEV_VECTORS";
    private static final String FIXTURE_PATH = "/canonical-v8-vectors.jsonl";
    private static final Map<String, ExpectedVector> EXPECTED = Map.of(
            "ACTIVITY:PER_MINUTE", new ExpectedVector(3, "DERIVED",
                    "63b6cf0ed4b25ae8a1a2b9ea1a8f50b1", "94b5d4b4e37acedb08aa94ece7f3244a"),
            "SCREEN_TIME:PER_HOUR", new ExpectedVector(-22, "MEASURED",
                    "1c2787d2721e8258e28abf746d497022", "e5b7fbdbad52e64e8f5a424069118ca3"),
            "SLEEP:PER_UNIT", new ExpectedVector(-36, "DERIVED",
                    "21e03a3451e19edc95c77a90024ad1b7", "04b0d744dad8b6323d311518b2018a83"),
            "ALCOHOL:PER_DRINK", new ExpectedVector(-15, "DERIVED",
                    "e1d01b651b729a459225d628f279cfb8", "e682dc6de482f71decd7319cfbc006eb"),
            "FOOD:PER_SERVING", new ExpectedVector(18, "DERIVED",
                    "88a7be5635504556d76d03b768ddbe50", "c3793b769cee0ad841e3b347eafb1006")
    );

    @Test
    void canonicalDevVectorsMatchExactConversionEngine() throws Exception {
        String pathValue = System.getenv(VECTOR_PATH_ENV);
        boolean required = Boolean.parseBoolean(System.getenv(REQUIRE_ENV));
        if (pathValue == null || pathValue.isBlank()) {
            if (required) {
                fail("MISSING_PREREQUISITE CANONICAL_DEV_VECTOR_PATH");
            }
            Assumptions.assumeTrue(false, "CANONICAL_DEV_VECTOR_PATH not provided");
        }

        Path vectorPath = Path.of(pathValue);
        if (!Files.isRegularFile(vectorPath)) {
            fail("MISSING_PREREQUISITE CANONICAL_DEV_VECTOR_PATH");
        }

        assertVectors(Files.readAllBytes(vectorPath));
    }

    @Test
    void checkedInCanonicalV8FixtureMatchesExactConversionEngine() throws Exception {
        try (var input = getClass().getResourceAsStream(FIXTURE_PATH)) {
            assertThat(input).as("canonical V8 vector fixture").isNotNull();
            assertVectors(input.readAllBytes());
        }
    }

    private void assertVectors(byte[] payloadBytes) throws Exception {
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] lines = payload.lines()
                .filter(line -> !line.isBlank())
                .toArray(String[]::new);
        if (lines.length == 0) {
            fail("EMPTY_CANONICAL_VECTOR_SET");
        }

        ExactConversionEngine engine = new ExactConversionEngine();
        Set<String> logicalKeyHashes = new HashSet<>();
        Set<String> selectors = new HashSet<>();
        EnumSet<HabitCategory> categories = EnumSet.noneOf(HabitCategory.class);
        for (String line : lines) {
            JsonNode vector = OBJECT_MAPPER.readTree(line);
            assertAllowedShape(vector);

            String logicalKeyHash = requiredText(vector, "logical_key_hash", "AMBIGUOUS_CANONICAL_RULE_SET");
            if (!logicalKeyHashes.add(logicalKeyHash)) {
                fail("AMBIGUOUS_CANONICAL_RULE_SET");
            }

            HabitCategory category = category(vector);
            ConversionUnit unit = unit(vector);
            categories.add(category);
            String selector = category.name() + ':' + unit.name();
            if (!selectors.add(selector)) {
                fail("DUPLICATE_CANONICAL_SELECTOR");
            }
            ExpectedVector expected = EXPECTED.get(selector);
            if (expected == null) {
                fail("UNEXPECTED_CANONICAL_SELECTOR");
            }
            int minutesDelta = requiredInt(vector, "minutes_delta", "UNSUPPORTED_CANONICAL_MINUTES_DELTA");
            assertThat(minutesDelta).as(selector + " minutes_delta").isEqualTo(expected.minutesDelta());
            assertThat(requiredText(vector, "evidence_class", "UNSUPPORTED_CANONICAL_EVIDENCE_CLASS"))
                    .as(selector + " evidence_class").isEqualTo(expected.evidenceClass());
            assertThat(logicalKeyHash).as(selector + " logical_key_hash")
                    .isEqualTo(expected.ruleLogicalKeyHash());
            assertThat(requiredText(vector, "source_logical_key_hash", "AMBIGUOUS_CANONICAL_SOURCE_SET"))
                    .as(selector + " source_logical_key_hash")
                    .isEqualTo(expected.sourceLogicalKeyHash());
            assertThat(requiredInt(vector, "rule_version_number", "UNSUPPORTED_CANONICAL_RULE_VERSION"))
                    .as(selector + " rule_version_number").isEqualTo(1);
            assertThat(requiredInt(vector, "source_version_number", "UNSUPPORTED_CANONICAL_SOURCE_VERSION"))
                    .as(selector + " source_version_number").isEqualTo(1);
            boolean sourceActive = requiredBoolean(vector, "source_active", "UNSUPPORTED_CANONICAL_SOURCE_STATE");
            if (!sourceActive) {
                fail("INACTIVE_SOURCE_FOR_ACTIVE_RULE");
            }
            requireConditionJson(vector);

            BigDecimal inputValue = unit == ConversionUnit.PER_1000_STEPS
                    ? new BigDecimal("1000")
                    : BigDecimal.ONE;
            long expectedSeconds = minutesDelta * 60L;

            var result = engine.calculate(
                    new ConversionInput(category, unit, inputValue),
                    new RuleTerms(category, unit, minutesDelta, true, sourceActive)
            );

            assertThat(result.postedSeconds()).isEqualTo(expectedSeconds);
            assertThat(result.exactSeconds()).isEqualByComparingTo(BigDecimal.valueOf(expectedSeconds).setScale(6));
        }

        if (!categories.equals(EnumSet.allOf(HabitCategory.class))) {
            fail("MISSING_CANONICAL_CATEGORY_COVERAGE");
        }
        assertThat(selectors).containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());

        System.out.println("CANONICAL_VECTOR_COUNT=" + lines.length);
        System.out.println("CANONICAL_VECTOR_SHA256=" + sha256(payloadBytes));
    }

    private static void assertAllowedShape(JsonNode vector) {
        Set<String> allowed = Set.of(
                "logical_key_hash",
                "source_logical_key_hash",
                "rule_version_number",
                "source_version_number",
                "evidence_class",
                "category",
                "unit",
                "minutes_delta",
                "condition_json",
                "source_active"
        );
        vector.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                fail("UNSUPPORTED_CANONICAL_VECTOR_SHAPE");
            }
        });
        for (String field : allowed) {
            if (!vector.has(field) || vector.get(field).isNull()) {
                fail("UNSUPPORTED_CANONICAL_VECTOR_SHAPE");
            }
        }
    }

    private static HabitCategory category(JsonNode vector) {
        return switch (requiredText(vector, "category", "UNSUPPORTED_CANONICAL_CATEGORY")) {
            case "sleep" -> HabitCategory.SLEEP;
            case "activity" -> HabitCategory.ACTIVITY;
            case "screen_time" -> HabitCategory.SCREEN_TIME;
            case "food" -> HabitCategory.FOOD;
            case "alcohol" -> HabitCategory.ALCOHOL;
            default -> {
                fail("UNSUPPORTED_CANONICAL_CATEGORY");
                yield null;
            }
        };
    }

    private static ConversionUnit unit(JsonNode vector) {
        String value = requiredText(vector, "unit", "UNSUPPORTED_CANONICAL_UNIT");
        try {
            return ConversionUnit.parse(value);
        } catch (RuntimeException exception) {
            fail("UNSUPPORTED_CANONICAL_UNIT");
            return null;
        }
    }

    private static void requireConditionJson(JsonNode vector) {
        JsonNode condition = vector.get("condition_json");
        if (!condition.isObject() || condition.size() != 0) {
            fail("UNSUPPORTED_CANONICAL_CONDITION_JSON");
        }
    }

    private static String requiredText(JsonNode vector, String field, String errorCode) {
        JsonNode value = vector.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            fail(errorCode);
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode vector, String field, String errorCode) {
        JsonNode value = vector.get(field);
        if (value == null || !value.isInt()) {
            fail(errorCode);
        }
        return value.asInt();
    }

    private static boolean requiredBoolean(JsonNode vector, String field, String errorCode) {
        JsonNode value = vector.get(field);
        if (value == null || !value.isBoolean()) {
            fail(errorCode);
        }
        return value.asBoolean();
    }

    private static String sha256(byte[] payload) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format(Locale.ROOT, "%02x", value));
        }
        return hex.toString();
    }

    private record ExpectedVector(
            int minutesDelta,
            String evidenceClass,
            String ruleLogicalKeyHash,
            String sourceLogicalKeyHash
    ) {
    }
}
