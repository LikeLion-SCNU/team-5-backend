package org.example.naeilbank.service;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.audit.AuditAppendService;
import org.example.naeilbank.domain.consent.ConsentDtos.ChangeRequest;
import org.example.naeilbank.domain.consent.ConsentDtos.ConsentListResponse;
import org.example.naeilbank.domain.consent.ConsentDtos.ConsentStatus;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.repository.ConsentRepository;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.example.naeilbank.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConsentService {
    private final ConsentRepository consentRepository;
    private final AuditAppendService auditAppendService;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ConsentListResponse getStatuses(UUID userId) {
        Map<Consent.Purpose, Consent> stored = consentRepository.findAllByUserId(userId).stream()
                .collect(Collectors.toMap(Consent::getPurpose, Function.identity()));
        return new ConsentListResponse(Arrays.stream(Consent.Purpose.values())
                .map(purpose -> stored.containsKey(purpose)
                        ? ConsentStatus.from(stored.get(purpose), false)
                        : ConsentStatus.missing(purpose))
                .toList());
    }

    @Transactional
    public ConsentStatus change(UUID userId, Consent.Purpose purpose, ChangeRequest request) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
        String normalizedTextHash = request.textHash().toLowerCase(Locale.ROOT);
        String requestKeyHash = sha256(request.idempotencyKey());
        String fingerprint = fingerprint(purpose, request, normalizedTextHash);

        var replay = auditAppendService.findConsentReplay(userId, requestKeyHash);
        if (replay.isPresent()) {
            if (!replay.get().requestFingerprint().equals(fingerprint)) {
                throw new AuthException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            Consent consent = consentRepository.findByIdAndUserId(replay.get().consentId(), userId)
                    .orElseThrow(() -> new AuthException(ErrorCode.CONSENT_VERSION_CONFLICT));
            if (!consent.matches(request.granted(), request.consentVersion(), normalizedTextHash)) {
                throw new AuthException(ErrorCode.CONSENT_VERSION_CONFLICT);
            }
            return ConsentStatus.from(consent, true);
        }

        Consent consent = consentRepository.findForUpdate(userId, purpose).orElse(null);
        if (consent == null) {
            if (request.expectedVersion() != 0L) {
                throw new AuthException(ErrorCode.CONSENT_VERSION_CONFLICT);
            }
            consent = Consent.create(
                    userId,
                    purpose,
                    request.granted(),
                    request.consentVersion(),
                    normalizedTextHash,
                    Instant.now(clock)
            );
        } else {
            if (consent.resourceVersion() != request.expectedVersion()) {
                throw new AuthException(ErrorCode.CONSENT_VERSION_CONFLICT);
            }
            if (request.consentVersion() < consent.getConsentVersion()) {
                throw new AuthException(ErrorCode.CONSENT_VERSION_CONFLICT);
            }
            consent.replace(
                    request.granted(),
                    request.consentVersion(),
                    normalizedTextHash,
                    Instant.now(clock)
            );
        }

        Consent saved = consentRepository.saveAndFlush(consent);
        auditAppendService.appendConsentChange(userId, saved, requestKeyHash, fingerprint);
        return ConsentStatus.from(saved, false);
    }

    private String fingerprint(Consent.Purpose purpose, ChangeRequest request, String normalizedTextHash) {
        return sha256(String.join("|",
                purpose.name(),
                request.granted().toString(),
                request.consentVersion().toString(),
                normalizedTextHash,
                request.expectedVersion().toString()
        ));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
