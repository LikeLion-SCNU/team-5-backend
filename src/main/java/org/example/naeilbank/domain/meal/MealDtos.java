package org.example.naeilbank.domain.meal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.example.naeilbank.domain.conversion.ConversionUnit;
import org.example.naeilbank.domain.conversion.HabitCategory;
import org.example.naeilbank.entity.MealRecord;
import org.example.naeilbank.entity.MealStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class MealDtos {
    private MealDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateMealRequest(
            @JsonProperty("media_blob_id") @NotNull UUID mediaBlobId,
            @JsonProperty("record_date") @NotNull LocalDate recordDate
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConfirmMealRequest(
            @JsonProperty("exclude_item_ids") Set<UUID> excludeItemIds,
            @JsonProperty("user_items") List<@Valid UserMealItem> userItems
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UserMealItem(
            @JsonProperty("food_name") @NotBlank String foodName,
            String portion,
            @NotNull HabitCategory category,
            @NotNull ConversionUnit unit,
            @NotNull BigDecimal value,
            MealEligibility eligibility
    ) {
    }

    public record MealView(
            UUID id,
            @JsonProperty("record_date") LocalDate recordDate,
            @JsonProperty("media_blob_id") UUID mediaBlobId,
            MealStatus status,
            @JsonProperty("confirmed_at") Instant confirmedAt,
            List<MealItemView> items
    ) {
        static MealView from(MealRecord record, List<MealItemView> items) {
            return new MealView(record.getId(), record.getRecordDate(), record.getMediaBlobId(),
                    record.getStatus(), record.getConfirmedAt(), items);
        }
    }

    public record MealItemView(
            UUID id,
            @JsonProperty("food_name") String foodName,
            String portion,
            HabitCategory category,
            ConversionUnit unit,
            BigDecimal value,
            MealEligibility eligibility,
            boolean deleted,
            @JsonProperty("user_added") boolean userAdded,
            @JsonProperty("rule_id") UUID ruleId
    ) {
    }
}
