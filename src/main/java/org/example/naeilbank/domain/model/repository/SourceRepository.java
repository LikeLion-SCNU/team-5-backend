package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.entity.Source;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SourceRepository extends JpaRepository<Source, UUID> {
    List<Source> findByActiveTrue();
}
