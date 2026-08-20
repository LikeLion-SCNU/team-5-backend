package org.example.naeilbank.domain.conversion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionReceipt;
import org.example.naeilbank.domain.conversion.ConversionModels.ExactResult;
import org.example.naeilbank.domain.conversion.ConversionModels.SnapshotBundle;
import org.example.naeilbank.entity.ConversionRule;
import org.example.naeilbank.entity.Source;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "conversion_postings")
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversionPosting {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;
    @Column(name = "source_event_type", nullable = false, updatable = false)
    private String sourceEventType;
    @Column(name = "entry_date", nullable = false, updatable = false)
    private LocalDate entryDate;
    @Column(name = "habit_type", nullable = false, updatable = false)
    private String habitType;
    @Column(name = "input_value", nullable = false, updatable = false, precision = 22, scale = 12)
    private BigDecimal inputValue;
    @Column(name = "input_unit", nullable = false, updatable = false)
    private String inputUnit;
    @Column(name = "posted_seconds", nullable = false, updatable = false)
    private long postedSeconds;
    @Column(name = "ledger_minutes_delta", nullable = false, updatable = false)
    private int ledgerMinutesDelta;
    @Column(name = "rule_id", nullable = false, updatable = false)
    private UUID ruleId;
    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;
    @Column(name = "ledger_entry_id", nullable = false, updatable = false)
    private long ledgerEntryId;
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_snapshot_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String ruleSnapshotJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_snapshot_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String sourceSnapshotJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String inputSnapshotJson;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_snapshot_json", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String resultSnapshotJson;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    static ConversionPosting create(UUID userId, ConversionCommand command, ConversionRule rule,
                                    Source source, ExactResult result, long ledgerEntryId,
                                    String requestHash, SnapshotBundle snapshots, Instant now) {
        ConversionPosting posting = new ConversionPosting();
        posting.userId = userId;
        posting.sourceEventId = command.sourceEventId();
        posting.sourceEventType = command.sourceType().persistedValue();
        posting.entryDate = command.entryDate();
        posting.habitType = command.category().persistedValue().name();
        posting.inputValue = command.value().setScale(ExactConversionEngine.CALCULATION_SCALE);
        posting.inputUnit = command.unit().persistedValue();
        posting.postedSeconds = result.postedSeconds();
        posting.ledgerMinutesDelta = result.ledgerMinutes();
        posting.ruleId = rule.getId();
        posting.sourceId = source.getId();
        posting.ledgerEntryId = ledgerEntryId;
        posting.requestHash = requestHash;
        posting.ruleSnapshotJson = snapshots.ruleJson();
        posting.sourceSnapshotJson = snapshots.sourceJson();
        posting.inputSnapshotJson = snapshots.inputJson();
        posting.resultSnapshotJson = snapshots.resultJson();
        posting.createdAt = now;
        return posting;
    }

    boolean matches(String hash) {
        return requestHash.equals(hash);
    }

    ConversionReceipt receipt(boolean replayed) {
        return new ConversionReceipt(ledgerEntryId, ruleId, postedSeconds, ledgerMinutesDelta, replayed);
    }
}
