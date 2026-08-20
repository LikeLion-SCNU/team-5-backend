package org.example.naeilbank.domain.consent;

import lombok.RequiredArgsConstructor;
import org.example.naeilbank.domain.model.entity.Consent;
import org.example.naeilbank.domain.model.repository.ConsentRepository;
import org.example.naeilbank.global.exception.AuthException;
import org.example.naeilbank.global.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ConsentGuard {
    private final ConsentRepository consentRepository;

    @Transactional(readOnly = true)
    public void requireGranted(UUID userId, Consent.Purpose purpose) {
        boolean granted = consentRepository.findByUserIdAndPurpose(userId, purpose)
                .map(Consent::isGranted)
                .orElse(false);
        if (!granted) {
            throw new AuthException(ErrorCode.CONSENT_REQUIRED);
        }
    }
}
