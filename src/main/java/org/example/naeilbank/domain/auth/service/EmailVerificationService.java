package org.example.naeilbank.domain.auth.service;

import org.example.naeilbank.entity.User;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EmailVerificationService {
    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();
    // 코드 무차별 대입 방지. 단일 인스턴스 배포 전제의 인메모리 카운터(FaceSimulationInterlock과 동일 전제).
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();

    private final boolean enabled;
    private final String fromAddress;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final Clock clock;

    public EmailVerificationService(
            @Value("${app.email-verification.enabled:false}") boolean enabled,
            @Value("${spring.mail.username:no-reply@timebank.local}") String fromAddress,
            ObjectProvider<JavaMailSender> mailSender,
            Clock clock
    ) {
        this.enabled = enabled;
        this.fromAddress = fromAddress;
        this.mailSender = mailSender;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 코드를 발급해 사용자에 기록하고 메일을 보낸다. 발송 실패는 가입을 막지 않는다(재전송 가능). */
    public void issueAndSend(User user) {
        // 실패 횟수는 코드를 새로 보내도 유지한다. 재전송으로 잠금을 풀 수 있으면 잠금이 아니다.
        if (failedAttempts.getOrDefault(user.getEmail(), 0) >= MAX_VERIFY_ATTEMPTS) {
            throw new AuthException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        user.issueEmailVerification(code, Instant.now(clock).plus(CODE_TTL));
        send(user.getEmail(), user.getName(), code);
    }

    public void verify(User user, String code) {
        if (user.isEmailVerified()) {
            return;
        }
        if (user.getEmailVerificationCode() == null || user.getEmailVerificationExpiresAt() == null) {
            throw new AuthException(ErrorCode.VERIFICATION_NOT_PENDING);
        }
        if (Instant.now(clock).isAfter(user.getEmailVerificationExpiresAt())) {
            failedAttempts.remove(user.getEmail());
            throw new AuthException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }
        if (failedAttempts.getOrDefault(user.getEmail(), 0) >= MAX_VERIFY_ATTEMPTS) {
            throw new AuthException(ErrorCode.VERIFICATION_CODE_EXPIRED);
        }
        if (!user.getEmailVerificationCode().equals(code)) {
            failedAttempts.merge(user.getEmail(), 1, Integer::sum);
            throw new AuthException(ErrorCode.INVALID_VERIFICATION_CODE);
        }
        failedAttempts.remove(user.getEmail());
        user.markEmailVerified();
    }

    private void send(String to, String name, String code) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("메일 전송기가 설정되지 않아 인증 코드를 발송하지 못했습니다. email={}", to);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject("[시간은행] 이메일 인증 코드");
        message.setText("""
                %s님, 시간은행 가입을 환영합니다.

                인증 코드: %s

                이 코드는 10분간 유효합니다.
                본인이 요청하지 않았다면 이 메일을 무시해주세요.
                """.formatted(name == null ? "회원" : name, code));
        try {
            sender.send(message);
        } catch (Exception exception) {
            log.warn("인증 메일 발송 실패 email={} cause={}", to, exception.getMessage());
        }
    }
}
