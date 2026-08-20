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
import org.example.naeilbank.domain.health.HealthDtos.ScreenMetric;
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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HealthService {
    private static final int SHORT_SLEEP_THRESHOLD_MINUTES = 7 * 60;
    private static final int ACTIVITY_CREDIT_CAP_MINUTES = 20;
    private static final int SCREEN_DEBIT_CAP_MINUTES = 6 * 60;
    private static final int DAILY_MINUTES_LIMIT = 24 * 60;
    /** 명백한 오염 입력만 거르는 하루 걸음 상한 */
    private static final int DAILY_STEPS_LIMIT = 200_000;
    private static final BigDecimal MINUTES_PER_HOUR = new BigDecimal("60");
    private static final int INPUT_SCALE = 12;

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
        rejectCorrection(healthDaily.getSleepMinutes(), request.sleepMinutes());
        rejectCorrection(healthDaily.getSteps(), request.steps());
        rejectCorrection(healthDaily.getModerateActivityMinutes(), request.moderateActivityMinutes());
        rejectCorrection(healthDaily.getScreenMinutes(), request.screenMinutes());
        healthDaily.mergeMissing(request.sleepMinutes(), request.steps(),
                request.moderateActivityMinutes(), request.screenMinutes());
        healthDaily = healthDailyRepository.saveAndFlush(healthDaily);

        List<ConversionReceipt> receipts = new ArrayList<>();
        if (request.sleepMinutes() != null
                && request.sleepMinutes() < SHORT_SLEEP_THRESHOLD_MINUTES) {
            receipts.add(convert(userId, healthDaily, HabitCategory.SLEEP,
                    ConversionUnit.PER_UNIT, BigDecimal.ONE));
        }
        if (request.moderateActivityMinutes() != null && request.moderateActivityMinutes() > 0) {
            receipts.add(convert(userId, healthDaily, HabitCategory.ACTIVITY,
                    ConversionUnit.PER_MINUTE, BigDecimal.valueOf(
                            Math.min(request.moderateActivityMinutes(), ACTIVITY_CREDIT_CAP_MINUTES))));
        }
        if (request.screenMinutes() != null && request.screenMinutes() > 0) {
            receipts.add(convert(userId, healthDaily, HabitCategory.SCREEN_TIME,
                    ConversionUnit.PER_HOUR, decimalHours(
                            Math.min(request.screenMinutes(), SCREEN_DEBIT_CAP_MINUTES))));
        }
        return HealthDailyResponse.from(healthDaily, receipts);
    }

    private ConversionReceipt convert(UUID userId, HealthDaily healthDaily,
                                      HabitCategory category, ConversionUnit unit, BigDecimal value) {
        return conversionService.convert(userId, new ConversionCommand(
                healthDaily.getId(),
                ConversionSourceType.HEALTH_DAILY,
                category,
                unit,
                value,
                healthDaily.getRecordDate()
        ));
    }

    private void validate(UpsertHealthDailyRequest request) {
        if (request.sleepMinutes() == null && request.steps() == null
                && request.moderateActivityMinutes() == null && request.screenMinutes() == null) {
            throw new AuthException(ErrorCode.INVALID_HEALTH_DATA);
        }
        requireNonNegative(request.sleepMinutes());
        requireNonNegative(request.steps());
        requireDailyMinutes(request.sleepMinutes());
        requireDailyMinutes(request.moderateActivityMinutes());
        requireDailyMinutes(request.screenMinutes());
        requireScreenMetric(request.screenMinutes(), request.screenMetric());
    }

    private void requireNonNegative(Integer value) {
        if (value != null && value < 0) {
            throw new AuthException(ErrorCode.INVALID_HEALTH_DATA);
        }
    }

    private void requireDailyMinutes(Integer value) {
        if (value != null && (value < 0 || value > DAILY_MINUTES_LIMIT)) {
            throw new AuthException(ErrorCode.INVALID_HEALTH_DATA);
        }
    }

    private void requireScreenMetric(Integer screenMinutes, ScreenMetric screenMetric) {
        if ((screenMinutes == null) != (screenMetric == null)
                || screenMetric != null && screenMetric != ScreenMetric.SEDENTARY_TV_EQUIVALENT) {
            throw new AuthException(ErrorCode.INVALID_HEALTH_DATA);
        }
    }

    private BigDecimal decimalHours(int minutes) {
        return BigDecimal.valueOf(minutes)
                .divide(MINUTES_PER_HOUR, INPUT_SCALE, RoundingMode.HALF_EVEN)
                .stripTrailingZeros();
    }

    private void rejectCorrection(Integer current, Integer incoming) {
        if (current != null && incoming != null && !current.equals(incoming)) {
            throw new AuthException(ErrorCode.HEALTH_DATA_CONFLICT);
        }
    }
}
