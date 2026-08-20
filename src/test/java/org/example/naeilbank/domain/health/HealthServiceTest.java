package org.example.naeilbank.domain.health;

import org.example.naeilbank.domain.consent.ConsentGuard;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionReceipt;
import org.example.naeilbank.domain.conversion.ConversionService;
import org.example.naeilbank.domain.conversion.ConversionSourceType;
import org.example.naeilbank.domain.conversion.ConversionUnit;
import org.example.naeilbank.domain.conversion.HabitCategory;
import org.example.naeilbank.domain.health.HealthDtos.UpsertHealthDailyRequest;
import org.example.naeilbank.domain.health.HealthDtos.ScreenMetric;
import org.example.naeilbank.domain.model.repository.HealthDailyRepository;
import org.example.naeilbank.entity.HealthDaily;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HealthServiceTest {
    private final HealthDailyRepository healthRepository = mock(HealthDailyRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ConsentGuard consentGuard = mock(ConsentGuard.class);
    private final ConversionService conversionService = mock(ConversionService.class);

    private HealthService service;

    @BeforeEach
    void setUp() {
        service = new HealthService(healthRepository, userRepository, consentGuard, conversionService);
        when(userRepository.findByIdForUpdate(any())).thenReturn(Optional.of(mock(User.class)));
        when(healthRepository.findByUserIdAndRecordDateForUpdate(any(), any()))
                .thenReturn(Optional.empty());
        when(healthRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            HealthDaily saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });
        when(conversionService.convert(any(), any()))
                .thenReturn(new ConversionReceipt(1, UUID.randomUUID(), 60, 1, false));
    }

    @Test
    void normalizesShortSleepAndCapsMeasuredModerateActivityWithoutConvertingSteps() {
        UUID userId = UUID.randomUUID();
        LocalDate recordDate = LocalDate.of(2026, 8, 20);

        var response = service.upsert(userId,
                new UpsertHealthDailyRequest(recordDate, 360, 2500, 45, null, null));

        ArgumentCaptor<ConversionCommand> commands = ArgumentCaptor.forClass(ConversionCommand.class);
        verify(conversionService, org.mockito.Mockito.times(2)).convert(
                org.mockito.ArgumentMatchers.eq(userId), commands.capture());
        assertThat(commands.getAllValues()).containsExactly(
                new ConversionCommand(commands.getAllValues().get(0).sourceEventId(),
                        ConversionSourceType.HEALTH_DAILY, HabitCategory.SLEEP,
                        ConversionUnit.PER_UNIT, BigDecimal.ONE, recordDate),
                new ConversionCommand(commands.getAllValues().get(0).sourceEventId(),
                        ConversionSourceType.HEALTH_DAILY, HabitCategory.ACTIVITY,
                        ConversionUnit.PER_MINUTE, new BigDecimal("20"), recordDate)
        );
        assertThat(response.steps()).isEqualTo(2500);
        assertThat(response.moderateActivityMinutes()).isEqualTo(45);
    }

    @Test
    void doesNotPostSleepAtOrAboveSevenHours() {
        var response = service.upsert(UUID.randomUUID(),
                new UpsertHealthDailyRequest(LocalDate.of(2026, 8, 21), 420,
                        null, null, null, null));

        assertThat(response.conversions()).isEqualTo(List.of());
        verifyNoInteractions(conversionService);
    }

    @Test
    void convertsAffirmedSedentaryTvEquivalentMinutesToDecimalHours() {
        UUID userId = UUID.randomUUID();
        LocalDate recordDate = LocalDate.of(2026, 8, 22);

        service.upsert(userId, new UpsertHealthDailyRequest(recordDate, null, null, null, 90,
                ScreenMetric.SEDENTARY_TV_EQUIVALENT));

        ArgumentCaptor<ConversionCommand> command = ArgumentCaptor.forClass(ConversionCommand.class);
        verify(conversionService).convert(org.mockito.ArgumentMatchers.eq(userId), command.capture());
        assertThat(command.getValue()).isEqualTo(new ConversionCommand(
                command.getValue().sourceEventId(), ConversionSourceType.HEALTH_DAILY,
                HabitCategory.SCREEN_TIME, ConversionUnit.PER_HOUR, new BigDecimal("1.5"), recordDate));
    }

    @Test
    void rejectsGenericScreenMinutesWithoutTvEquivalentAffirmation() {
        assertThatThrownBy(() -> service.upsert(UUID.randomUUID(),
                new UpsertHealthDailyRequest(LocalDate.of(2026, 8, 23), null,
                        null, null, 90, null)))
                .isInstanceOf(org.example.naeilbank.global.exception.AuthException.class)
                .hasMessage("건강 데이터 요청이 올바르지 않습니다.");

        verifyNoInteractions(userRepository, consentGuard, healthRepository, conversionService);
    }
}
