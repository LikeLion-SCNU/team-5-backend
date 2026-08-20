package org.example.naeilbank.domain.notification;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.model.entity.NotificationAttempt;
import org.example.naeilbank.domain.model.entity.NotificationPreference;
import org.example.naeilbank.domain.model.entity.WebPushSubscription;
import org.example.naeilbank.domain.model.repository.NotificationAttemptRepository;
import org.example.naeilbank.domain.model.repository.NotificationPreferenceRepository;
import org.example.naeilbank.domain.model.repository.WebPushSubscriptionRepository;
import org.example.naeilbank.domain.notification.NotificationDtos.PreferenceRequest;
import org.example.naeilbank.domain.notification.NotificationDtos.PreferenceResponse;
import org.example.naeilbank.domain.notification.NotificationDtos.SubscriptionRequest;
import org.example.naeilbank.domain.notification.NotificationDtos.SubscriptionResponse;
import org.example.naeilbank.global.config.properties.VapidProperties;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private static final int MAX_ATTEMPTS = 3;

    private final WebPushSubscriptionRepository subscriptionRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationAttemptRepository attemptRepository;
    private final UserRepository userRepository;
    private final WebPushSender webPushSender;
    private final VapidProperties vapidProperties;
    private final Clock clock;

    @Transactional
    public SubscriptionResponse register(UUID userId, SubscriptionRequest request) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        String endpointHash = sha256(request.endpoint());
        String endpoint = encode(request.endpoint());
        String p256dh = encode(request.keys().p256dh());
        String auth = encode(request.keys().auth());
        WebPushSubscription subscription = subscriptionRepository.findByEndpointHash(endpointHash)
                .map(existing -> {
                    if (!existing.getUserId().equals(userId)) {
                        throw new AuthException(ErrorCode.ACCESS_DENIED);
                    }
                    existing.refresh(endpoint, p256dh, auth, request.expirationTime());
                    return existing;
                })
                .orElseGet(() -> WebPushSubscription.create(
                        userId,
                        endpointHash,
                        endpoint,
                        p256dh,
                        auth,
                        request.expirationTime(),
                        Instant.now(clock)
                ));
        return toResponse(subscriptionRepository.save(subscription));
    }

    @Transactional
    public void revoke(UUID userId, UUID subscriptionId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        WebPushSubscription subscription = subscriptionRepository.findByIdAndUserId(subscriptionId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.ACCESS_DENIED));
        subscription.deactivate();
    }

    @Transactional
    public PreferenceResponse updatePreference(UUID userId, PreferenceRequest request) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND))
                .changeNotificationPreference(request.enabled(), request.morningTime());
        ZoneId.of(request.timezone());
        NotificationPreference preference = preferenceRepository.findById(userId)
                .orElseGet(() -> NotificationPreference.create(
                        userId,
                        request.enabled(),
                        request.timezone(),
                        request.morningTime(),
                        Instant.now(clock)
                ));
        preference.update(request.enabled(), request.timezone(), request.morningTime());
        return toResponse(preferenceRepository.save(preference));
    }

    @Transactional(readOnly = true)
    public PreferenceResponse preference(UUID userId) {
        NotificationPreference preference = preferenceRepository.findById(userId)
                .orElseGet(() -> NotificationPreference.create(userId, true, "Asia/Seoul", LocalTime.of(8, 0), Instant.now(clock)));
        return toResponse(preference);
    }

    public String publicKey() {
        return vapidProperties.publicKey();
    }

    @Transactional
    public int enqueueDueMorningStatements() {
        Instant now = Instant.now(clock);
        int created = 0;
        for (NotificationPreference preference : preferenceRepository.findByEnabledTrue()) {
            ZoneId zone = ZoneId.of(preference.getTimezone());
            LocalTime localTime = LocalTime.ofInstant(now, zone).withSecond(0).withNano(0);
            if (!localTime.equals(preference.getMorningTime().withSecond(0).withNano(0))) {
                continue;
            }
            LocalDate localDate = LocalDate.ofInstant(now, zone);
            created += enqueueMorningStatement(preference.getUserId(), localDate, now);
        }
        return created;
    }

    @Transactional
    public int processDueAttempts() {
        Instant now = Instant.now(clock);
        List<NotificationAttempt> due = attemptRepository.findDueForUpdate(
                List.of(NotificationAttempt.Status.pending, NotificationAttempt.Status.retry),
                now
        );
        int processed = 0;
        for (NotificationAttempt attempt : due) {
            processAttempt(attempt, now);
            processed++;
        }
        return processed;
    }

    private int enqueueMorningStatement(UUID userId, LocalDate localDate, Instant now) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        if (attemptRepository.existsByUserIdAndLocalDateAndType(userId, localDate, NotificationAttempt.Type.morning_statement)) {
            return 0;
        }
        List<WebPushSubscription> subscriptions = subscriptionRepository.findByUserIdAndActiveTrue(userId);
        if (subscriptions.isEmpty()) {
            return 0;
        }
        try {
            attemptRepository.save(NotificationAttempt.pending(
                    userId,
                    subscriptions.getFirst().getId(),
                    localDate,
                    NotificationAttempt.Type.morning_statement,
                    now
            ));
            return 1;
        } catch (DataIntegrityViolationException e) {
            return 0;
        }
    }

    private void processAttempt(NotificationAttempt attempt, Instant now) {
        WebPushSubscription subscription = subscriptionRepository.findByIdAndUserId(attempt.getSubscriptionId(), attempt.getUserId())
                .orElse(null);
        if (subscription == null || !subscription.isActive()) {
            attempt.markCancelled();
            return;
        }
        attempt.markProcessing();
        WebPushSender.SendResult result = webPushSender.send(new WebPushSender.WebPushMessage(
                decode(subscription.getEndpointCiphertext()),
                decode(subscription.getP256dhCiphertext()),
                decode(subscription.getAuthCiphertext()),
                audience(decode(subscription.getEndpointCiphertext()))
        ));
        switch (result) {
            case accepted -> attempt.markSent();
            case expired -> {
                subscription.deactivate();
                attempt.markCancelled();
            }
            case transient_failure -> {
                if (attempt.getAttemptCount() + 1 >= MAX_ATTEMPTS) {
                    attempt.markFailed();
                } else {
                    attempt.markRetry(now.plus(Duration.ofMinutes(5)));
                }
            }
            case permanent_failure -> attempt.markFailed();
        }
    }

    private SubscriptionResponse toResponse(WebPushSubscription subscription) {
        return new SubscriptionResponse(subscription.getId(), subscription.isActive(), subscription.getExpirationTime());
    }

    private PreferenceResponse toResponse(NotificationPreference preference) {
        return new PreferenceResponse(preference.isEnabled(), preference.getTimezone(), preference.getMorningTime());
    }

    private String audience(String endpoint) {
        URI uri = URI.create(endpoint);
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(padBase64Url(value)), StandardCharsets.UTF_8);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String padBase64Url(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "=".repeat(4 - remainder);
    }
}
