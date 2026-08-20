package org.example.naeilbank.domain.conversion;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface ConversionPostingRepository extends Repository<ConversionPosting, UUID> {
    Optional<ConversionPosting> findByUserIdAndSourceEventTypeAndSourceEventIdAndHabitType(
            UUID userId,
            String sourceEventType,
            UUID sourceEventId,
            String habitType
    );

    ConversionPosting saveAndFlush(ConversionPosting posting);
}
