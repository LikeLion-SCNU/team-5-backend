package org.example.naeilbank.domain.health;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionReceipt;
import org.example.naeilbank.entity.HealthDaily;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class HealthDtos {
    private HealthDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UpsertHealthDailyRequest(
            @JsonProperty("record_date") @NotNull LocalDate recordDate,
            @JsonProperty("sleep_minutes") Integer sleepMinutes,
            Integer steps,
            @JsonProperty("screen_minutes") Integer screenMinutes
    ) {
    }

    public record HealthDailyResponse(
            UUID id,
            @JsonProperty("record_date") LocalDate recordDate,
            @JsonProperty("sleep_minutes") Integer sleepMinutes,
            Integer steps,
            @JsonProperty("screen_minutes") Integer screenMinutes,
            @JsonProperty("sync_status") HealthDaily.SyncStatus syncStatus,
            List<ConversionReceipt> conversions
    ) {
        static HealthDailyResponse from(HealthDaily healthDaily, List<ConversionReceipt> conversions) {
            return new HealthDailyResponse(
                    healthDaily.getId(),
                    healthDaily.getRecordDate(),
                    healthDaily.getSleepMinutes(),
                    healthDaily.getSteps(),
                    healthDaily.getScreenMinutes(),
                    healthDaily.getSyncStatus(),
                    conversions
            );
        }
    }
}
