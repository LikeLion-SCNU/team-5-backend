package org.example.naeilbank.domain.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.example.naeilbank.entity.ConversionRule;
import org.example.naeilbank.entity.LedgerEntry;
import org.example.naeilbank.entity.Source;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class EvidenceDtos {
    private EvidenceDtos() {
    }

    public record SourceContent(
            @NotBlank @Size(max = 500) String title,
            @Size(max = 1000) String authors,
            @Size(max = 500) String journal,
            @Min(1800) @Max(2200) Integer publicationYear,
            @NotBlank @Size(max = 1000) String doiUrl,
            @NotBlank @Size(max = 8000) String summaryKo,
            @NotBlank @Size(max = 8000) String scopeKo,
            @NotBlank @Size(max = 8000) String limitationsKo
    ) {
    }

    public record RuleContent(
            @NotNull ConversionRule.HabitType habitType,
            @NotBlank @Size(max = 500) String label,
            @NotNull JsonNode condition,
            @NotNull @Min(-525600) @Max(525600) Integer minutesDelta,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$") String unit,
            @NotNull UUID sourceId
    ) {
    }

    public record CreateSourceRequest(@NotNull @Valid SourceContent content, @NotNull Boolean active) {
    }

    public record VersionSourceRequest(
            @NotNull @Valid SourceContent content,
            @NotNull Boolean active,
            @NotNull @PositiveOrZero Long expectedVersion
    ) {
    }

    public record CreateRuleRequest(@NotNull @Valid RuleContent content, @NotNull Boolean active) {
    }

    public record VersionRuleRequest(
            @NotNull @Valid RuleContent content,
            @NotNull Boolean active,
            @NotNull @PositiveOrZero Long expectedVersion
    ) {
    }

    public record ActivationRequest(
            @NotNull Boolean active,
            @NotNull @PositiveOrZero Long expectedVersion
    ) {
    }

    public record SourceView(
            UUID id,
            UUID logicalKey,
            int versionNumber,
            String title,
            String authors,
            String journal,
            Integer publicationYear,
            String doiUrl,
            String summaryKo,
            String scopeKo,
            String limitationsKo,
            boolean active,
            long resourceVersion,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static SourceView from(Source source) {
            return new SourceView(source.getId(), source.getLogicalKey(), source.getVersionNumber(),
                    source.getTitle(), source.getAuthors(), source.getJournal(),
                    source.getPublicationYear(), source.getDoiUrl(), source.getSummaryKo(),
                    source.getScopeKo(), source.getLimitationsKo(), source.isActive(),
                    source.resourceVersion(), source.getCreatedAt(), source.getUpdatedAt());
        }
    }

    public record RuleView(
            UUID id,
            UUID logicalKey,
            int versionNumber,
            ConversionRule.HabitType habitType,
            String label,
            JsonNode condition,
            int minutesDelta,
            String unit,
            boolean active,
            long resourceVersion,
            Instant createdAt,
            Instant updatedAt,
            SourceView source
    ) {
    }

    public record LedgerEvidenceView(
            long ledgerEntryId,
            LocalDate entryDate,
            ConversionRule.HabitType habitType,
            int minutesDelta,
            String referenceType,
            UUID referenceId,
            Instant createdAt,
            RuleView rule
    ) {
        public static LedgerEvidenceView from(LedgerEntry entry, RuleView rule) {
            return new LedgerEvidenceView(entry.getId(), entry.getEntryDate(), entry.getHabitType(),
                    entry.getMinutesDelta(), entry.getReferenceType(), entry.getReferenceId(),
                    entry.getCreatedAt(), rule);
        }
    }

    public record SourceListResponse(List<SourceView> sources) {
        public SourceListResponse {
            sources = List.copyOf(sources);
        }
    }

    public record RuleListResponse(List<RuleView> rules) {
        public RuleListResponse {
            rules = List.copyOf(rules);
        }
    }
}
