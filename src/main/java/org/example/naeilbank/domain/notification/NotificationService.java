package org.example.naeilbank.domain.notification;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.consent.ConsentGuard;
import org.example.naeilbank.domain.model.entity.Consent;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
    private final WebPushCipher webPushCipher;
    private final ConsentGuard consentGuard;
    private final Clock clock;

    @Transactional
    public SubscriptionResponse register(UUID userId, SubscriptionRequest request) {
        ValidatedSubscription validated = validateSubscription(request);
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        consentGuard.requireGranted(userId, Consent.Purpose.NOTIFICATION);
        String endpointHash = sha256(validated.endpoint());
        String endpoint = webPushCipher.encrypt("endpoint", validated.endpoint());
        String p256dh = webPushCipher.encrypt("p256dh", request.keys().p256dh());
        String auth = webPushCipher.encrypt("auth", request.keys().auth());
        WebPushSubscription subscription = subscriptionRepository.findByEndpointHash(endpointHash)
                .map(existing -> {
                    if (!existing.getUserId().equals(userId)) {
                        throw new AuthException(ErrorCode.ACCESS_DENIED);
                    }
                    existing.refresh(endpointHash, endpoint, p256dh, auth, request.expirationTime());
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
        return saveSubscription(subscription);
    }

    @Transactional
    public SubscriptionResponse updateSubscription(UUID userId, UUID subscriptionId, SubscriptionRequest request) {
        ValidatedSubscription validated = validateSubscription(request);
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        consentGuard.requireGranted(userId, Consent.Purpose.NOTIFICATION);
        WebPushSubscription subscription = subscriptionRepository.findByIdAndUserId(subscriptionId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.ACCESS_DENIED));
        String endpointHash = sha256(validated.endpoint());
        subscriptionRepository.findByEndpointHash(endpointHash).ifPresent(endpointOwner -> {
            if (!endpointOwner.getId().equals(subscriptionId)) {
                throw new AuthException(ErrorCode.ACCESS_DENIED);
            }
        });
        subscription.refresh(
                endpointHash,
                webPushCipher.encrypt("endpoint", validated.endpoint()),
                webPushCipher.encrypt("p256dh", request.keys().p256dh()),
                webPushCipher.encrypt("auth", request.keys().auth()),
                request.expirationTime()
        );
        return saveSubscription(subscription);
    }

    @Transactional
    public void revoke(UUID userId, UUID subscriptionId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        WebPushSubscription subscription = subscriptionRepository.findByIdAndUserId(subscriptionId, userId)
                .orElseThrow(() -> new AuthException(ErrorCode.ACCESS_DENIED));
        subscriptionRepository.delete(subscription);
    }

    @Transactional
    public PreferenceResponse updatePreference(UUID userId, PreferenceRequest request) {
        validatePreference(request);
        var user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        if (request.enabled()) {
            consentGuard.requireGranted(userId, Consent.Purpose.NOTIFICATION);
        }
        user.changeNotificationPreference(request.enabled(), request.morningTime());
        String timezone = ZoneId.of(request.timezone()).getId();
        NotificationPreference preference = preferenceRepository.findById(userId)
                .orElseGet(() -> NotificationPreference.create(
                        userId,
                        request.enabled(),
                        timezone,
                        request.morningTime(),
                        Instant.now(clock)
                ));
        preference.update(request.enabled(), timezone, request.morningTime());
        return toResponse(preferenceRepository.save(preference));
    }

    @Transactional(readOnly = true)
    public PreferenceResponse preference(UUID userId) {
        NotificationPreference preference = preferenceRepository.findById(userId)
                .orElseGet(() -> NotificationPreference.create(userId, false, "Asia/Seoul", LocalTime.of(8, 0), Instant.now(clock)));
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
        if (subscription.getExpirationTime() != null && !subscription.getExpirationTime().isAfter(now)) {
            subscription.deactivate();
            attempt.markCancelled();
            return;
        }
        attempt.markProcessing();
        WebPushSender.SendResult result = webPushSender.send(new WebPushSender.WebPushMessage(
                webPushCipher.decrypt("endpoint", subscription.getEndpointCiphertext()),
                webPushCipher.decrypt("p256dh", subscription.getP256dhCiphertext()),
                webPushCipher.decrypt("auth", subscription.getAuthCiphertext()),
                audience(webPushCipher.decrypt("endpoint", subscription.getEndpointCiphertext()))
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

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private SubscriptionResponse saveSubscription(WebPushSubscription subscription) {
        try {
            return toResponse(subscriptionRepository.saveAndFlush(subscription));
        } catch (DataIntegrityViolationException e) {
            throw new AuthException(ErrorCode.ACCESS_DENIED);
        }
    }

    private ValidatedSubscription validateSubscription(SubscriptionRequest request) {
        if (request == null || request.endpoint() == null || request.keys() == null
                || request.keys().p256dh() == null || request.keys().auth() == null) {
            throw new AuthException(ErrorCode.VALIDATION_FAILED);
        }
        String endpoint;
        try {
            URI uri = URI.create(request.endpoint());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("invalid endpoint");
            }
            int port = uri.getPort() == 443 ? -1 : uri.getPort();
            endpoint = new URI(
                    "https",
                    null,
                    uri.getHost().toLowerCase(Locale.ROOT),
                    port,
                    uri.getRawPath(),
                    uri.getRawQuery(),
                    null
            ).normalize().toASCIIString();
            byte[] p256dh = java.util.Base64.getUrlDecoder().decode(request.keys().p256dh());
            byte[] auth = java.util.Base64.getUrlDecoder().decode(request.keys().auth());
            if (p256dh.length != 65 || p256dh[0] != 4 || auth.length != 16) {
                throw new IllegalArgumentException("invalid keys");
            }
        } catch (Exception e) {
            throw new AuthException(ErrorCode.VALIDATION_FAILED);
        }
        if (request.expirationTime() != null && !request.expirationTime().isAfter(Instant.now(clock))) {
            throw new AuthException(ErrorCode.VALIDATION_FAILED);
        }
        return new ValidatedSubscription(endpoint);
    }

    private void validatePreference(PreferenceRequest request) {
        if (request == null || request.enabled() == null || request.timezone() == null
                || request.timezone().isBlank() || request.morningTime() == null
                || request.morningTime().getSecond() != 0 || request.morningTime().getNano() != 0) {
            throw new AuthException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            ZoneId.of(request.timezone());
        } catch (Exception e) {
            throw new AuthException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private record ValidatedSubscription(String endpoint) {
    }
}
