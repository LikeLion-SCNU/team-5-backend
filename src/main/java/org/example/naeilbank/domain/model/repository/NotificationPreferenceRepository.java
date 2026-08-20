package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
    List<NotificationPreference> findByEnabledTrue();
}
