package org.example.naeilbank.domain.auth.service;

import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailVerificationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @SuppressWarnings("unchecked")
    private EmailVerificationService service(boolean enabled, Instant now) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new EmailVerificationService(enabled, "no-reply@test", provider,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issueStoresSixDigitCodeAndVerifyMarksVerified() {
        EmailVerificationService service = service(true, NOW);
        User user = User.local("a@b.c", "pw", "홍길동");

        service.issueAndSend(user);

        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getEmailVerificationCode()).matches("\\d{6}");
        assertThat(user.getEmailVerificationExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));

        service.verify(user, user.getEmailVerificationCode());

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getEmailVerificationCode()).isNull();
        assertThat(user.getEmailVerificationExpiresAt()).isNull();
    }

    @Test
    void wrongCodeExpiredCodeAndMissingIssueEachFailClosed() {
        EmailVerificationService issuedAt = service(true, NOW);
        User user = User.local("a@b.c", "pw", "홍길동");

        assertThatThrownBy(() -> issuedAt.verify(user, "123456"))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VERIFICATION_NOT_PENDING));

        issuedAt.issueAndSend(user);
        assertThatThrownBy(() -> issuedAt.verify(user, "000000".equals(user.getEmailVerificationCode()) ? "111111" : "000000"))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE));

        EmailVerificationService later = service(true, NOW.plus(Duration.ofMinutes(11)));
        assertThatThrownBy(() -> later.verify(user, user.getEmailVerificationCode()))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VERIFICATION_CODE_EXPIRED));
    }

    @Test
    void verifyingAnAlreadyVerifiedUserIsIdempotent() {
        EmailVerificationService service = service(true, NOW);
        User user = User.local("a@b.c", "pw", "홍길동");
        user.markEmailVerified();

        service.verify(user, "whatever");

        assertThat(user.isEmailVerified()).isTrue();
    }
}
