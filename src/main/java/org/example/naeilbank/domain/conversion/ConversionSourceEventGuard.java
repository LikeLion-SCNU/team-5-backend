package org.example.naeilbank.domain.conversion;

import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
import org.example.naeilbank.domain.model.repository.HealthDailyRepository;
import org.example.naeilbank.domain.model.repository.MealItemRepository;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.entity.MealStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ConversionSourceEventGuard {
    private final HealthDailyRepository healthRepository;
    private final MealItemRepository mealItemRepository;

    public ConversionSourceEventGuard(HealthDailyRepository healthRepository,
                                      MealItemRepository mealItemRepository) {
        this.healthRepository = healthRepository;
        this.mealItemRepository = mealItemRepository;
    }

    public void requireOwned(UUID userId, ConversionCommand command) {
        boolean exists = switch (command.sourceType()) {
            case HEALTH_DAILY -> {
                requireHealthCategory(command.category());
                yield healthRepository.existsByIdAndUserIdAndRecordDate(
                        command.sourceEventId(), userId, command.entryDate());
            }
            case MEAL_ITEM -> {
                requireMealCategory(command.category());
                yield mealItemRepository.existsOwnedEvent(
                        command.sourceEventId(), userId, command.entryDate(), MealStatus.confirmed);
            }
        };
        if (!exists) {
            throw new AuthException(ErrorCode.CONVERSION_SOURCE_EVENT_NOT_FOUND);
        }
    }

    private void requireHealthCategory(HabitCategory category) {
        if (category != HabitCategory.SLEEP && category != HabitCategory.ACTIVITY
                && category != HabitCategory.SCREEN_TIME) {
            throw new AuthException(ErrorCode.UNSUPPORTED_CONVERSION_SOURCE);
        }
    }

    private void requireMealCategory(HabitCategory category) {
        if (category != HabitCategory.FOOD && category != HabitCategory.ALCOHOL) {
            throw new AuthException(ErrorCode.UNSUPPORTED_CONVERSION_SOURCE);
        }
    }
}
