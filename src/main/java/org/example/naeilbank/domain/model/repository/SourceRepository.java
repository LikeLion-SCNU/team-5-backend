package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.entity.Source;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceRepository extends JpaRepository<Source, UUID> {
    List<Source> findByActiveTrueOrderByTitleAscVersionNumberDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Source s where s.id = :id")
    Optional<Source> findByIdForUpdate(@Param("id") UUID id);

    Optional<Source> findFirstByLogicalKeyOrderByVersionNumberDesc(UUID logicalKey);
}
