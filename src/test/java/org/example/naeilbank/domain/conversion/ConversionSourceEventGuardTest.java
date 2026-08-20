package org.example.naeilbank.domain.conversion;

import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
import org.example.naeilbank.domain.model.repository.HealthDailyRepository;
import org.example.naeilbank.domain.model.repository.MealItemRepository;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.entity.MealStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversionSourceEventGuardTest {
    private final HealthDailyRepository healthRepository = mock(HealthDailyRepository.class);
    private final MealItemRepository mealRepository = mock(MealItemRepository.class);
    private final ConversionSourceEventGuard guard = new ConversionSourceEventGuard(
            healthRepository, mealRepository);

    @Test
    void ownedHealthAndMealEventsAreVerifiedThroughTenantScopedQueries() {
        UUID userId = UUID.randomUUID();
        ConversionCommand health = command(ConversionSourceType.HEALTH_DAILY, HabitCategory.ACTIVITY);
        when(healthRepository.existsByIdAndUserIdAndRecordDate(
                health.sourceEventId(), userId, health.entryDate())).thenReturn(true);
        guard.requireOwned(userId, health);
        verify(healthRepository).existsByIdAndUserIdAndRecordDate(
                health.sourceEventId(), userId, health.entryDate());

        ConversionCommand meal = command(ConversionSourceType.MEAL_ITEM, HabitCategory.FOOD);
        when(mealRepository.existsOwnedEvent(
                meal.sourceEventId(), userId, meal.entryDate(), MealStatus.confirmed)).thenReturn(true);
        guard.requireOwned(userId, meal);
        verify(mealRepository).existsOwnedEvent(
                meal.sourceEventId(), userId, meal.entryDate(), MealStatus.confirmed);
    }

    @Test
    void missingWrongOwnerAndIncompatibleSourceTypesFailClosed() {
        UUID userId = UUID.randomUUID();
        assertError(() -> guard.requireOwned(userId,
                command(ConversionSourceType.HEALTH_DAILY, HabitCategory.SLEEP)),
                ErrorCode.CONVERSION_SOURCE_EVENT_NOT_FOUND);
        assertError(() -> guard.requireOwned(userId,
                command(ConversionSourceType.MEAL_ITEM, HabitCategory.ACTIVITY)),
                ErrorCode.UNSUPPORTED_CONVERSION_SOURCE);
        verifyNoInteractions(mealRepository);
    }

    private ConversionCommand command(ConversionSourceType type, HabitCategory category) {
        return new ConversionCommand(UUID.randomUUID(), type, category, ConversionUnit.PER_UNIT,
                BigDecimal.ONE, LocalDate.of(2026, 8, 20));
    }

    private void assertError(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AuthException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
