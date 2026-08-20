package org.example.naeilbank.domain.notification;

import org.example.naeilbank.domain.consent.ConsentGuard;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.entity.NotificationAttempt;
import org.example.naeilbank.domain.model.entity.NotificationPreference;
import org.example.naeilbank.domain.model.entity.WebPushSubscription;
import org.example.naeilbank.domain.model.repository.NotificationAttemptRepository;
import org.example.naeilbank.domain.model.repository.NotificationPreferenceRepository;
import org.example.naeilbank.domain.model.repository.WebPushSubscriptionRepository;
import org.example.naeilbank.domain.notification.NotificationDtos.PushKeys;
import org.example.naeilbank.domain.notification.NotificationDtos.PreferenceRequest;
import org.example.naeilbank.domain.notification.NotificationDtos.PreferenceResponse;
import org.example.naeilbank.domain.notification.NotificationDtos.SubscriptionRequest;
import org.example.naeilbank.domain.notification.NotificationDtos.SubscriptionResponse;
import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.config.properties.VapidProperties;
import org.example.naeilbank.global.config.properties.WebPushEncryptionProperties;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
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
import static org.mockito.Mockito.doThrow;
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
    private final ConsentGuard consentGuard = mock(ConsentGuard.class);

    @Test
    void sameOwnerRegistrationRefreshesExistingSubscriptionAndPreservesId() {
        UUID userId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        WebPushSubscription existing = subscription(userId, subscriptionId, "https://push.example/sub");
        existing.deactivate();
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(User.local("u@example.com", "pw")));
        when(subscriptions.findByEndpointHash(any())).thenReturn(Optional.of(existing));
        when(subscriptions.saveAndFlush(existing)).thenReturn(existing);

        SubscriptionResponse response = service(Instant.parse("2026-08-20T00:00:00Z"))
                .register(userId, request("https://push.example/sub"));

        assertThat(response.id()).isEqualTo(subscriptionId);
        assertThat(response.active()).isTrue();
    }

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
    void rejectsNonHttpsEndpointAndMalformedWebPushKeys() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service(Instant.parse("2026-08-20T00:00:00Z"))
                .register(userId, new SubscriptionRequest(
                        "http://push.example/sub",
                        new PushKeys("not-a-p256-key", "not-an-auth-secret"),
                        null
                )))
                .isInstanceOf(RuntimeException.class);

        verify(users, never()).findByIdForUpdate(any());
    }

    @Test
    void rejectsAlreadyExpiredSubscription() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service(Instant.parse("2026-08-20T00:00:00Z"))
                .register(userId, new SubscriptionRequest(
                        "https://push.example/sub",
                        validKeys(),
                        Instant.parse("2026-08-19T23:59:59Z")
                )))
                .isInstanceOf(RuntimeException.class);

        verify(users, never()).findByIdForUpdate(any());
    }

    @Test
    void encryptsSubscriptionSecretsAtRest() {
        UUID userId = UUID.randomUUID();
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(User.local("u@example.com", "pw")));
        when(subscriptions.findByEndpointHash(any())).thenReturn(Optional.empty());
        when(subscriptions.saveAndFlush(any(WebPushSubscription.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service(Instant.parse("2026-08-20T00:00:00Z"))
                .register(userId, new SubscriptionRequest("https://push.example/secret", validKeys(), null));

        ArgumentCaptor<WebPushSubscription> captor = ArgumentCaptor.forClass(WebPushSubscription.class);
        verify(subscriptions).saveAndFlush(captor.capture());
        WebPushSubscription stored = captor.getValue();
        assertThat(stored.getEndpointCiphertext()).startsWith("v1.").doesNotContain("push.example");
        assertThat(stored.getP256dhCiphertext()).startsWith("v1.").doesNotContain(validKeys().p256dh());
        assertThat(stored.getAuthCiphertext()).startsWith("v1.").doesNotContain(validKeys().auth());
        assertThat(cipher().decrypt("endpoint", stored.getEndpointCiphertext()))
                .isEqualTo("https://push.example/secret");
        assertThat(cipher().decrypt("p256dh", stored.getP256dhCiphertext())).isEqualTo(validKeys().p256dh());
        assertThat(cipher().decrypt("auth", stored.getAuthCiphertext())).isEqualTo(validKeys().auth());
    }

    @Test
    void notificationConsentIsRequiredForRegistrationRenewalAndEnabling() {
        UUID userId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(User.local("u@example.com", "pw")));
        doThrow(new AuthException(org.example.naeilbank.global.exception.ErrorCode.CONSENT_REQUIRED))
                .when(consentGuard).requireGranted(userId, Consent.Purpose.NOTIFICATION);

        NotificationService service = service(Instant.parse("2026-08-20T00:00:00Z"));

        assertThatThrownBy(() -> service.register(userId, request("https://push.example/register")))
                .isInstanceOf(AuthException.class)
                .extracting(exception -> ((AuthException) exception).getErrorCode())
                .isEqualTo(org.example.naeilbank.global.exception.ErrorCode.CONSENT_REQUIRED);
        assertThatThrownBy(() -> service.updateSubscription(
                userId,
                subscriptionId,
                request("https://push.example/renew")
        )).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.updatePreference(
                userId,
                new PreferenceRequest(true, "Asia/Seoul", LocalTime.of(8, 0))
        )).isInstanceOf(AuthException.class);
        verify(subscriptions, never()).saveAndFlush(any());
        verify(preferences, never()).save(any());
    }

    @Test
    void distinctEndpointsCreateIndependentDeviceSubscriptions() {
        UUID userId = UUID.randomUUID();
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(User.local("u@example.com", "pw")));
        when(subscriptions.findByEndpointHash(any())).thenReturn(Optional.empty());
        when(subscriptions.saveAndFlush(any(WebPushSubscription.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NotificationService service = service(Instant.parse("2026-08-20T00:00:00Z"));
        service.register(userId, request("https://push.example/device-a"));
        service.register(userId, request("https://push.example/device-b"));

        ArgumentCaptor<WebPushSubscription> captor = ArgumentCaptor.forClass(WebPushSubscription.class);
        verify(subscriptions, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(WebPushSubscription::getEndpointHash)
                .doesNotHaveDuplicates();
        assertThat(captor.getAllValues())
                .extracting(value -> cipher().decrypt("endpoint", value.getEndpointCiphertext()))
                .containsExactly("https://push.example/device-a", "https://push.example/device-b");
    }

    @Test
    void absentPreferenceAndNewUserDoNotInferNotificationConsent() {
        UUID userId = UUID.randomUUID();
        when(preferences.findById(userId)).thenReturn(Optional.empty());

        PreferenceResponse response = service(Instant.parse("2026-08-20T00:00:00Z")).preference(userId);

        assertThat(response.enabled()).isFalse();
        assertThat(User.local("u@example.com", "pw").isNotifyEnabled()).isFalse();
    }

    @Test
    void rejectsPreferenceTimeWithSecondPrecision() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> service(Instant.parse("2026-08-20T00:00:00Z"))
                .updatePreference(userId, new PreferenceRequest(true, "Asia/Seoul", LocalTime.of(8, 0, 1))))
                .isInstanceOf(RuntimeException.class);

        verify(users, never()).findByIdForUpdate(any());
    }

    @Test
    void cancelsLocallyExpiredSubscriptionWithoutSending() {
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
        ReflectionTestUtils.setField(subscription, "expirationTime", Instant.parse("2026-08-19T23:59:59Z"));
        when(attempts.findDueForUpdate(any(), eq(Instant.parse("2026-08-20T00:00:00Z")))).thenReturn(List.of(attempt));
        when(subscriptions.findByIdAndUserId(subscriptionId, userId)).thenReturn(Optional.of(subscription));

        service(Instant.parse("2026-08-20T00:00:00Z")).processDueAttempts();

        assertThat(subscription.isActive()).isFalse();
        assertThat(attempt.getStatus()).isEqualTo(NotificationAttempt.Status.cancelled);
        verify(sender, never()).send(any());
    }

    @Test
    void ownerCanRotateEndpointAndKeysButCannotTakeOverAnotherEndpoint() {
        UUID owner = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        WebPushSubscription owned = subscription(owner, subscriptionId, "https://push.example/old");
        when(users.findByIdForUpdate(owner)).thenReturn(Optional.of(User.local("u@example.com", "pw")));
        when(subscriptions.findByIdAndUserId(subscriptionId, owner)).thenReturn(Optional.of(owned));
        when(subscriptions.findByEndpointHash(any())).thenReturn(Optional.empty());
        when(subscriptions.saveAndFlush(owned)).thenReturn(owned);

        SubscriptionResponse response = service(Instant.parse("2026-08-20T00:00:00Z"))
                .updateSubscription(owner, subscriptionId, request("https://push.example/new"));

        assertThat(response.id()).isEqualTo(subscriptionId);
        assertThat(cipher().decrypt("endpoint", owned.getEndpointCiphertext()))
                .isEqualTo("https://push.example/new");

        UUID otherOwner = UUID.randomUUID();
        WebPushSubscription other = subscription(otherOwner, UUID.randomUUID(), "https://push.example/taken");
        when(subscriptions.findByEndpointHash(any())).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service(Instant.parse("2026-08-20T00:00:00Z"))
                .updateSubscription(owner, subscriptionId, request("https://push.example/taken")))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void revokeRequiresOwnerAndDeletesSubscriptionSecrets() {
        UUID owner = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        WebPushSubscription owned = subscription(owner, subscriptionId, "https://push.example/sub");
        when(users.findByIdForUpdate(attacker)).thenReturn(Optional.of(User.local("a@example.com", "pw")));
        when(subscriptions.findByIdAndUserId(subscriptionId, attacker)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(Instant.parse("2026-08-20T00:00:00Z"))
                .revoke(attacker, subscriptionId))
                .isInstanceOf(AuthException.class);
        verify(subscriptions, never()).delete(any());

        when(users.findByIdForUpdate(owner)).thenReturn(Optional.of(User.local("u@example.com", "pw")));
        when(subscriptions.findByIdAndUserId(subscriptionId, owner)).thenReturn(Optional.of(owned));

        service(Instant.parse("2026-08-20T00:00:00Z")).revoke(owner, subscriptionId);

        verify(subscriptions).delete(owned);
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
                cipher(),
                consentGuard,
                Clock.fixed(instant, ZoneOffset.UTC)
        );
    }

    private SubscriptionRequest request(String endpoint) {
        return new SubscriptionRequest(endpoint, validKeys(), null);
    }

    private PushKeys validKeys() {
        byte[] publicKey = new byte[65];
        publicKey[0] = 4;
        return new PushKeys(
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16])
        );
    }

    private WebPushSubscription subscription(UUID userId, UUID id, String endpoint) {
        WebPushSubscription subscription = WebPushSubscription.create(
                userId,
                "hash-" + id,
                cipher().encrypt("endpoint", endpoint),
                cipher().encrypt("p256dh", validKeys().p256dh()),
                cipher().encrypt("auth", validKeys().auth()),
                null,
                Instant.parse("2026-08-20T00:00:00Z")
        );
        ReflectionTestUtils.setField(subscription, "id", id);
        return subscription;
    }

    private WebPushCipher cipher() {
        return new WebPushCipher(new WebPushEncryptionProperties(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        ));
    }
}
