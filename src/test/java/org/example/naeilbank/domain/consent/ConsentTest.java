package org.example.naeilbank.domain.consent;

import org.example.naeilbank.domain.model.entity.Consent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ConsentTest {
    private static final String FIRST_HASH = "a".repeat(64);
    private static final String SECOND_HASH = "b".repeat(64);

    @Test
    void grantWithdrawAndReplacementMaintainLifecycleTimestamps() {
        Instant grantedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant revokedAt = grantedAt.plusSeconds(60);
        Consent consent = Consent.create(
                UUID.randomUUID(),
                Consent.Purpose.HEALTH_COLLECTION,
                true,
                1,
                FIRST_HASH,
                grantedAt
        );

        assertThat(consent.isGranted()).isTrue();
        assertThat(consent.getGrantedAt()).isEqualTo(grantedAt);
        assertThat(consent.getRevokedAt()).isNull();
        assertThat(consent.resourceVersion()).isEqualTo(1);

        consent.replace(false, 2, SECOND_HASH, revokedAt);

        assertThat(consent.isGranted()).isFalse();
        assertThat(consent.getGrantedAt()).isEqualTo(grantedAt);
        assertThat(consent.getRevokedAt()).isEqualTo(revokedAt);
        assertThat(consent.matches(false, 2, SECOND_HASH)).isTrue();
    }

    @Test
    void invalidVersionAndTextHashAreRejectedByDomainBoundary() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        assertThatIllegalArgumentException().isThrownBy(() -> Consent.create(
                userId, Consent.Purpose.MEAL_AI, true, 0, FIRST_HASH, now));
        assertThatIllegalArgumentException().isThrownBy(() -> Consent.create(
                userId, Consent.Purpose.MEAL_AI, true, 1, " ", now));
    }
}
