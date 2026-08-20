package org.example.naeilbank.domain.conversion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
import org.example.naeilbank.domain.conversion.ConversionModels.ExactResult;
import org.example.naeilbank.domain.conversion.ConversionModels.SnapshotBundle;
import org.example.naeilbank.entity.ConversionRule;
import org.example.naeilbank.entity.Source;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

final class ConversionSnapshotFactory {
    private final ObjectMapper objectMapper;

    ConversionSnapshotFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void requireUnconditional(ConversionRule rule) {
        try {
            JsonNode condition = objectMapper.readTree(rule.getConditionJson());
            if (condition == null || !condition.isObject() || !condition.isEmpty()) {
                throw new AuthException(ErrorCode.CONVERSION_CONDITION_UNSUPPORTED);
            }
        } catch (JsonProcessingException exception) {
            throw new AuthException(ErrorCode.CONVERSION_CONDITION_UNSUPPORTED);
        }
    }

    String fingerprint(ConversionCommand command) {
        requireShape(command);
        String canonical = String.join("|", "v1", command.sourceEventId().toString(),
                command.sourceType().persistedValue(), command.category().persistedValue().name(),
                command.unit().persistedValue(), decimal(command.value()), command.entryDate().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    void requireShape(ConversionCommand command) {
        if (command == null || command.sourceEventId() == null || command.sourceType() == null
                || command.category() == null || command.unit() == null || command.value() == null
                || command.entryDate() == null) {
            throw new AuthException(ErrorCode.CONVERSION_VALUE_OUT_OF_RANGE);
        }
    }

    SnapshotBundle snapshots(ConversionCommand command, ConversionRule rule,
                             Source source, ExactResult result) {
        return new SnapshotBundle(ruleJson(rule),
                sourceJson(source),
                json(Map.of("sourceEventId", command.sourceEventId(),
                        "sourceEventType", command.sourceType().persistedValue(),
                        "habitType", command.category().persistedValue(),
                        "unit", command.unit().persistedValue(), "value", decimal(command.value()),
                        "entryDate", command.entryDate().toString())),
                json(Map.of("normalizedUnits", result.normalizedUnits().toPlainString(),
                        "exactSeconds", result.exactSeconds().toPlainString(),
                        "postedSeconds", result.postedSeconds(),
                        "ledgerMinutes", result.ledgerMinutes(), "rounding", "HALF_EVEN")));
    }

    private String ruleJson(ConversionRule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", rule.getId().toString());
        node.put("logicalKey", rule.getLogicalKey().toString());
        node.put("version", rule.getVersionNumber());
        node.put("habitType", rule.getHabitType().name());
        node.put("label", rule.getLabel());
        node.set("condition", parsed(rule.getConditionJson()));
        node.put("minutesDelta", rule.getMinutesDelta());
        node.put("unit", rule.getUnit());
        node.put("sourceId", rule.getSourceId().toString());
        node.put("active", rule.isActive());
        node.put("rowVersion", rule.getRowVersion());
        node.put("createdAt", rule.getCreatedAt().toString());
        node.put("updatedAt", rule.getUpdatedAt().toString());
        return json(node);
    }

    private String sourceJson(Source source) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", source.getId().toString());
        node.put("logicalKey", source.getLogicalKey().toString());
        node.put("version", source.getVersionNumber());
        node.put("title", source.getTitle());
        put(node, "authors", source.getAuthors());
        put(node, "journal", source.getJournal());
        if (source.getPublicationYear() == null) {
            node.putNull("publicationYear");
        } else {
            node.put("publicationYear", source.getPublicationYear());
        }
        put(node, "doiUrl", source.getDoiUrl());
        put(node, "summaryKo", source.getSummaryKo());
        put(node, "scopeKo", source.getScopeKo());
        put(node, "limitationsKo", source.getLimitationsKo());
        node.put("active", source.isActive());
        node.put("rowVersion", source.getRowVersion());
        node.put("createdAt", source.getCreatedAt().toString());
        node.put("updatedAt", source.getUpdatedAt().toString());
        return json(node);
    }

    private void put(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private JsonNode parsed(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new AuthException(ErrorCode.CONVERSION_CONDITION_UNSUPPORTED);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Conversion snapshot serialization failed", exception);
        }
    }

    private String decimal(java.math.BigDecimal value) {
        java.math.BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }
}
