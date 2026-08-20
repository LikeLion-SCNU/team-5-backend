package org.example.naeilbank.domain.health;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.consent.ConsentGuard;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionReceipt;
import org.example.naeilbank.domain.conversion.ConversionService;
import org.example.naeilbank.domain.conversion.ConversionSourceType;
import org.example.naeilbank.domain.conversion.ConversionUnit;
import org.example.naeilbank.domain.conversion.HabitCategory;
import org.example.naeilbank.domain.health.HealthDtos.HealthDailyResponse;
import org.example.naeilbank.domain.health.HealthDtos.UpsertHealthDailyRequest;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.repository.HealthDailyRepository;
import org.example.naeilbank.entity.HealthDaily;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HealthService {
    private final HealthDailyRepository healthDailyRepository;
    private final UserRepository userRepository;
    private final ConsentGuard consentGuard;
    private final ConversionService conversionService;

    @Transactional
    public HealthDailyResponse upsert(UUID userId, UpsertHealthDailyRequest request) {
        validate(request);
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        consentGuard.requireGranted(userId, Consent.Purpose.HEALTH_COLLECTION);
        HealthDaily healthDaily = healthDailyRepository
                .findByUserIdAndRecordDateForUpdate(userId, request.recordDate())
                .orElseGet(() -> new HealthDaily(userId, request.recordDate()));
        healthDaily.replace(request.sleepMinutes(), request.steps(), request.screenMinutes());
        healthDaily = healthDailyRepository.saveAndFlush(healthDaily);

        List<ConversionReceipt> receipts = new ArrayList<>();
        if (request.sleepMinutes() != null) {
            receipts.add(convert(userId, healthDaily, HabitCategory.SLEEP,
                    ConversionUnit.PER_MINUTE, request.sleepMinutes()));
        }
        if (request.steps() != null) {
            receipts.add(convert(userId, healthDaily, HabitCategory.ACTIVITY,
                    ConversionUnit.PER_1000_STEPS, request.steps()));
        }
        if (request.screenMinutes() != null) {
            receipts.add(convert(userId, healthDaily, HabitCategory.SCREEN_TIME,
                    ConversionUnit.PER_MINUTE, request.screenMinutes()));
        }
        return HealthDailyResponse.from(healthDaily, receipts);
    }

    private ConversionReceipt convert(UUID userId, HealthDaily healthDaily,
                                      HabitCategory category, ConversionUnit unit, int value) {
        return conversionService.convert(userId, new ConversionCommand(
                healthDaily.getId(),
                ConversionSourceType.HEALTH_DAILY,
                category,
                unit,
                BigDecimal.valueOf(value),
                healthDaily.getRecordDate()
        ));
    }

    private void validate(UpsertHealthDailyRequest request) {
        if (request.sleepMinutes() == null && request.steps() == null && request.screenMinutes() == null) {
            throw new AuthException(ErrorCode.INVALID_HEALTH_DATA);
        }
        requireNonNegative(request.sleepMinutes());
        requireNonNegative(request.steps());
        requireNonNegative(request.screenMinutes());
    }

    private void requireNonNegative(Integer value) {
        if (value != null && value < 0) {
            throw new AuthException(ErrorCode.INVALID_HEALTH_DATA);
        }
    }
}
