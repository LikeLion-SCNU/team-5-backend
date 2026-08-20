package org.example.naeilbank.domain.meal;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.consent.ConsentGuard;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionCommand;
import org.example.naeilbank.domain.conversion.ConversionModels.ConversionReceipt;
import org.example.naeilbank.domain.conversion.ConversionService;
import org.example.naeilbank.domain.conversion.ConversionSourceType;
import org.example.naeilbank.domain.meal.MealAnalysisContract.AnalyzedMeal;
import org.example.naeilbank.domain.meal.MealDtos.ConfirmMealRequest;
import org.example.naeilbank.domain.meal.MealDtos.CreateMealRequest;
import org.example.naeilbank.domain.meal.MealDtos.MealView;
import org.example.naeilbank.domain.meal.MealDtos.UserMealItem;
import org.example.naeilbank.domain.media.MediaModels.MediaDownload;
import org.example.naeilbank.domain.media.MediaModels.MediaMetadata;
import org.example.naeilbank.domain.media.MediaService;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.entity.MediaBlob;
import org.example.naeilbank.domain.model.repository.MealItemRepository;
import org.example.naeilbank.domain.model.repository.MealRecordRepository;
import org.example.naeilbank.entity.MealItem;
import org.example.naeilbank.entity.MealRecord;
import org.example.naeilbank.entity.MealStatus;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MealService {
    private static final BigDecimal MAX_DAILY_FOOD_SERVINGS = new BigDecimal("5");
    private static final BigDecimal DAILY_NEUTRAL_ALCOHOL_SERVINGS = BigDecimal.ONE;
    private static final BigDecimal MAX_DAILY_PENALIZED_ALCOHOL_SERVINGS = new BigDecimal("6");

    private final MealRecordRepository mealRecordRepository;
    private final MealItemRepository mealItemRepository;
    private final UserRepository userRepository;
    private final ConsentGuard consentGuard;
    private final MediaService mediaService;
    private final MealAnalysisClient mealAnalysisClient;
    private final MealItemPayloadCodec payloadCodec;
    private final ConversionService conversionService;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    public MealView create(UUID userId, CreateMealRequest request) {
        UUID mealId = transaction().execute(status -> ensureRecord(userId, request));
        return retryAnalysis(userId, mealId);
    }

    public MealView retryAnalysis(UUID userId, UUID mealId) {
        MealStatus status = transaction().execute(tx -> {
            lockUser(userId);
            consentGuard.requireGranted(userId, Consent.Purpose.MEAL_AI);
            return mealRecordRepository.findByIdAndUserIdForUpdate(mealId, userId)
                    .orElseThrow(() -> new AuthException(ErrorCode.MEAL_NOT_FOUND))
                    .getStatus();
        });
        if (status != MealStatus.analyzing) {
            return get(userId, mealId);
        }
        UUID mediaBlobId = transaction().execute(tx -> mealRecordRepository
                .findByIdAndUserId(mealId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.MEAL_NOT_FOUND))
                .getMediaBlobId());
        MediaDownload download = mediaService.download(userId, mediaBlobId);
        transaction().executeWithoutResult(tx -> {
            lockUser(userId);
            consentGuard.requireGranted(userId, Consent.Purpose.MEAL_AI);
        });
        AnalyzedMeal analysis = mealAnalysisClient.analyze(download.metadata().contentType(), downloadContent(download));
        return transaction().execute(tx -> applyAnalysis(userId, mealId, analysis));
    }

    @Transactional(readOnly = true)
    public MealView get(UUID userId, UUID mealId) {
        MealRecord record = mealRecordRepository.findByIdAndUserId(mealId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.MEAL_NOT_FOUND));
        return view(record);
    }

    @Transactional
    public MealView confirm(UUID userId, UUID mealId, ConfirmMealRequest request) {
        lockUser(userId);
        consentGuard.requireGranted(userId, Consent.Purpose.MEAL_AI);
        MealRecord record = mealRecordRepository.findByIdAndUserIdForUpdate(mealId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.MEAL_NOT_FOUND));
        if (record.getStatus() == MealStatus.confirmed || record.getStatus() == MealStatus.excluded) {
            return view(record);
        }
        if (record.getStatus() != MealStatus.pending_confirm) {
            throw new AuthException(ErrorCode.MEAL_STATE_CONFLICT);
        }

        List<MealItem> items = new ArrayList<>(mealItemRepository.findByMealRecordIdOrderById(record.getId()));
        applyExclusions(items, request == null ? null : request.excludeItemIds());
        for (UserMealItem item : request == null || request.userItems() == null ? List.<UserMealItem>of() : request.userItems()) {
            items.add(payloadCodec.userItem(record.getId(), item));
        }
        mealItemRepository.saveAllAndFlush(items);
        List<MealItem> activeItems = items.stream().filter(item -> !item.isDeleted()).toList();
        if (activeItems.isEmpty()) {
            record.exclude(Instant.now(clock));
            mealRecordRepository.saveAndFlush(record);
            return view(record);
        }

        DailyMealConsumption consumption = dailyConsumption(userId, record);
        BigDecimal remainingFoodServings = MAX_DAILY_FOOD_SERVINGS
                .subtract(consumption.foodServings()).max(BigDecimal.ZERO);
        BigDecimal remainingNeutralAlcoholServings = DAILY_NEUTRAL_ALCOHOL_SERVINGS
                .subtract(consumption.alcoholServings()).max(BigDecimal.ZERO);
        BigDecimal priorPenalizedAlcoholServings = consumption.alcoholServings()
                .subtract(DAILY_NEUTRAL_ALCOHOL_SERVINGS).max(BigDecimal.ZERO);
        BigDecimal remainingPenalizedAlcoholServings = MAX_DAILY_PENALIZED_ALCOHOL_SERVINGS
                .subtract(priorPenalizedAlcoholServings).max(BigDecimal.ZERO);
        record.confirm(Instant.now(clock));
        mealRecordRepository.saveAndFlush(record);
        for (MealItem item : activeItems) {
            MealItemPayloadCodec.StoredPortion portion = payloadCodec.decode(item.getPortion());
            BigDecimal conversionValue;
            if (portion.category() == org.example.naeilbank.domain.conversion.HabitCategory.FOOD) {
                if (!portion.qualifiesForFoodCredit() || remainingFoodServings.signum() <= 0) {
                    continue;
                }
                conversionValue = portion.value().min(remainingFoodServings);
                remainingFoodServings = remainingFoodServings.subtract(conversionValue);
            } else {
                BigDecimal neutralServings = portion.value().min(remainingNeutralAlcoholServings);
                remainingNeutralAlcoholServings = remainingNeutralAlcoholServings.subtract(neutralServings);
                conversionValue = portion.value().subtract(neutralServings)
                        .min(remainingPenalizedAlcoholServings);
                if (conversionValue.signum() == 0) {
                    continue;
                }
                remainingPenalizedAlcoholServings = remainingPenalizedAlcoholServings.subtract(conversionValue);
            }
            ConversionReceipt receipt = conversionService.convert(userId, new ConversionCommand(
                    item.getId(),
                    ConversionSourceType.MEAL_ITEM,
                    portion.category(),
                    portion.unit(),
                    conversionValue,
                    record.getRecordDate()
            ));
            if (portion.category() == org.example.naeilbank.domain.conversion.HabitCategory.ALCOHOL
                    && receipt.postedSeconds() > 0) {
                throw new AuthException(ErrorCode.CONVERSION_RULE_UNAVAILABLE);
            }
            item.assignRule(receipt.ruleId());
        }
        mealItemRepository.saveAllAndFlush(activeItems);
        return view(record);
    }

    private DailyMealConsumption dailyConsumption(UUID userId, MealRecord record) {
        List<MealItemPayloadCodec.StoredPortion> confirmed = mealItemRepository.findOwnedActiveItemsForDate(
                        userId, record.getRecordDate(), MealStatus.confirmed).stream()
                .map(MealItem::getPortion)
                .map(payloadCodec::decode)
                .toList();
        BigDecimal foodServings = confirmed.stream()
                .filter(MealItemPayloadCodec.StoredPortion::qualifiesForFoodCredit)
                .map(MealItemPayloadCodec.StoredPortion::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal alcoholServings = confirmed.stream()
                .filter(portion -> portion.category()
                        == org.example.naeilbank.domain.conversion.HabitCategory.ALCOHOL)
                .map(MealItemPayloadCodec.StoredPortion::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new DailyMealConsumption(foodServings, alcoholServings);
    }

    private record DailyMealConsumption(BigDecimal foodServings, BigDecimal alcoholServings) {
    }

    @Transactional
    public MealView exclude(UUID userId, UUID mealId) {
        lockUser(userId);
        MealRecord record = mealRecordRepository.findByIdAndUserIdForUpdate(mealId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.MEAL_NOT_FOUND));
        if (record.getStatus() == MealStatus.confirmed) {
            throw new AuthException(ErrorCode.MEAL_STATE_CONFLICT);
        }
        if (record.getStatus() != MealStatus.excluded) {
            record.exclude(Instant.now(clock));
            mealRecordRepository.saveAndFlush(record);
        }
        return view(record);
    }

    private UUID ensureRecord(UUID userId, CreateMealRequest request) {
        lockUser(userId);
        consentGuard.requireGranted(userId, Consent.Purpose.MEAL_AI);
        MediaMetadata media = mediaService.metadata(userId, request.mediaBlobId());
        if (media.purpose() != MediaBlob.Purpose.meal_input) {
            throw new AuthException(ErrorCode.INVALID_MEAL_REQUEST);
        }
        MealRecord record = mealRecordRepository
                .findByUserIdAndMediaBlobIdForUpdate(userId, request.mediaBlobId())
                .orElseGet(() -> mealRecordRepository.saveAndFlush(
                        new MealRecord(userId, request.recordDate(), request.mediaBlobId(), Instant.now(clock))));
        return record.getId();
    }

    private MealView applyAnalysis(UUID userId, UUID mealId, AnalyzedMeal analysis) {
        MealRecord record = mealRecordRepository.findByIdAndUserIdForUpdate(mealId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.MEAL_NOT_FOUND));
        if (record.getStatus() != MealStatus.analyzing) {
            return view(record);
        }
        List<MealItem> items = analysis.items().stream()
                .map(item -> payloadCodec.aiItem(record.getId(), item))
                .toList();
        mealItemRepository.saveAllAndFlush(items);
        record.markPendingConfirm();
        mealRecordRepository.saveAndFlush(record);
        return view(record);
    }

    private byte[] downloadContent(MediaDownload download) {
        try (var output = new java.io.ByteArrayOutputStream()) {
            download.writeTo(output);
            return output.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read private meal media", e);
        }
    }

    private void applyExclusions(List<MealItem> items, Set<UUID> excludeIds) {
        Set<UUID> remaining = new HashSet<>(excludeIds == null ? Set.of() : excludeIds);
        for (MealItem item : items) {
            if (remaining.remove(item.getId())) {
                item.markDeleted();
            }
        }
        if (!remaining.isEmpty()) {
            throw new AuthException(ErrorCode.MEAL_NOT_FOUND);
        }
    }

    private MealView view(MealRecord record) {
        return MealView.from(record, mealItemRepository.findByMealRecordIdOrderById(record.getId())
                .stream()
                .map(payloadCodec::view)
                .toList());
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private void lockUser(UUID userId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
    }
}
