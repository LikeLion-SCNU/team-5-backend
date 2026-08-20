package org.example.naeilbank.domain.notification;

import org.example.naeilbank.domain.model.entity.NotificationAttempt;
import org.example.naeilbank.domain.model.entity.NotificationPreference;
import org.example.naeilbank.domain.model.entity.WebPushSubscription;
import org.example.naeilbank.domain.model.repository.NotificationAttemptRepository;
import org.example.naeilbank.domain.model.repository.NotificationPreferenceRepository;
import org.example.naeilbank.domain.model.repository.WebPushSubscriptionRepository;
import org.example.naeilbank.domain.notification.NotificationDtos.PushKeys;
import org.example.naeilbank.domain.notification.NotificationDtos.SubscriptionRequest;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.config.properties.VapidProperties;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    private final WebPushSubscriptionRepository subscriptions = mock(WebPushSubscriptionRepository.class);
    private final NotificationPreferenceRepository preferences = mock(NotificationPreferenceRepository.class);
    private final NotificationAttemptRepository attempts = mock(NotificationAttemptRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final WebPushSender sender = mock(WebPushSender.class);

    @Test
    void rejectsSubscriptionEndpointOwnedByAnotherUser() {
        UUID owner = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        WebPushSubscription existing = subscription(owner, UUID.randomUUID(), "https://push.example/sub");
        when(users.findByIdForUpdate(attacker)).thenReturn(Optional.of(User.local("a@example.com", "pw")));
        when(subscriptions.findByEndpointHash(any())).thenReturn(Optional.of(existing));

        NotificationService service = service(Instant.parse("2026-08-20T00:00:00Z"));

        assertThatThrownBy(() -> service.register(attacker, request("https://push.example/sub")))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void enqueuesMorningStatementOnceForUserLocalDateAcrossDuplicateWorkersAndDst() {
        UUID userId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        AtomicBoolean exists = new AtomicBoolean(false);
        NotificationPreference preference = NotificationPreference.create(
                userId,
                true,
                "America/New_York",
                LocalTime.of(8, 0),
                Instant.parse("2026-03-08T00:00:00Z")
        );
        when(preferences.findByEnabledTrue()).thenReturn(List.of(preference));
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(User.local("u@example.com", "pw")));
        when(attempts.existsByUserIdAndLocalDateAndType(userId, LocalDate.of(2026, 3, 8), NotificationAttempt.Type.morning_statement))
                .thenAnswer(invocation -> exists.get());
        when(subscriptions.findByUserIdAndActiveTrue(userId))
                .thenReturn(List.of(subscription(userId, subscriptionId, "https://push.example/sub")));
        when(attempts.save(any(NotificationAttempt.class))).thenAnswer(invocation -> {
            exists.set(true);
            return invocation.getArgument(0);
        });

        NotificationService service = service(Instant.parse("2026-03-08T12:00:00Z"));

        assertThat(service.enqueueDueMorningStatements()).isEqualTo(1);
        assertThat(service.enqueueDueMorningStatements()).isZero();
        verify(attempts).save(any(NotificationAttempt.class));
    }

    @Test
    void marksExpiredSubscriptionInactiveAndCancelsAttempt() {
        UUID userId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        NotificationAttempt attempt = NotificationAttempt.pending(
                userId,
                subscriptionId,
                LocalDate.of(2026, 8, 20),
                NotificationAttempt.Type.morning_statement,
                Instant.parse("2026-08-20T00:00:00Z")
        );
        WebPushSubscription subscription = subscription(userId, subscriptionId, "https://push.example/sub");
        when(attempts.findDueForUpdate(any(), eq(Instant.parse("2026-08-20T00:00:00Z")))).thenReturn(List.of(attempt));
        when(subscriptions.findByIdAndUserId(subscriptionId, userId)).thenReturn(Optional.of(subscription));
        when(sender.send(any())).thenReturn(WebPushSender.SendResult.expired);

        NotificationService service = service(Instant.parse("2026-08-20T00:00:00Z"));

        assertThat(service.processDueAttempts()).isEqualTo(1);
        assertThat(subscription.isActive()).isFalse();
        assertThat(attempt.getStatus()).isEqualTo(NotificationAttempt.Status.cancelled);
    }

    @Test
    void retriesTransientFailureWithoutDuplicatingSentState() {
        UUID userId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        NotificationAttempt attempt = NotificationAttempt.pending(
                userId,
                subscriptionId,
                LocalDate.of(2026, 8, 20),
                NotificationAttempt.Type.morning_statement,
                Instant.parse("2026-08-20T00:00:00Z")
        );
        when(attempts.findDueForUpdate(any(), eq(Instant.parse("2026-08-20T00:00:00Z")))).thenReturn(List.of(attempt));
        when(subscriptions.findByIdAndUserId(subscriptionId, userId))
                .thenReturn(Optional.of(subscription(userId, subscriptionId, "https://push.example/sub")));
        when(sender.send(any())).thenReturn(WebPushSender.SendResult.transient_failure);

        service(Instant.parse("2026-08-20T00:00:00Z")).processDueAttempts();

        assertThat(attempt.getStatus()).isEqualTo(NotificationAttempt.Status.retry);
        assertThat(attempt.getAttemptCount()).isEqualTo(1);
        assertThat(attempt.getNextAttemptAt()).isEqualTo(Instant.parse("2026-08-20T00:05:00Z"));
    }

    private NotificationService service(Instant instant) {
        return new NotificationService(
                subscriptions,
                preferences,
                attempts,
                users,
                sender,
                new VapidProperties("public", "private", "mailto:test@example.com"),
                Clock.fixed(instant, ZoneOffset.UTC)
        );
    }

    private SubscriptionRequest request(String endpoint) {
        return new SubscriptionRequest(endpoint, new PushKeys("p256dh", "auth"), null);
    }

    private WebPushSubscription subscription(UUID userId, UUID id, String endpoint) {
        WebPushSubscription subscription = WebPushSubscription.create(
                userId,
                "hash-" + id,
                encode(endpoint),
                encode("p256dh"),
                encode("auth"),
                null,
                Instant.parse("2026-08-20T00:00:00Z")
        );
        ReflectionTestUtils.setField(subscription, "id", id);
        return subscription;
    }

    private String encode(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
