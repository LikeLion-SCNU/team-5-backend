package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.WebPushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebPushSubscriptionRepository extends JpaRepository<WebPushSubscription, UUID> {
    Optional<WebPushSubscription> findByEndpointHash(String endpointHash);

    List<WebPushSubscription> findByUserIdAndActiveTrue(UUID userId);
}
