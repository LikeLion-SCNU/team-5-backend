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
    @Query("""
            select s from Source s
            where s.active = true
              and not exists (
                  select newer.id from Source newer
                  where newer.logicalKey = s.logicalKey
                    and newer.active = true
                    and newer.versionNumber > s.versionNumber
              )
            order by s.title asc
            """)
    List<Source> findLatestActiveVersions();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Source s where s.id = :id")
    Optional<Source> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Source s
            where s.logicalKey = (
                select target.logicalKey from Source target where target.id = :id
            )
            order by s.versionNumber asc
            """)
    List<Source> findFamilyByMemberIdForUpdate(@Param("id") UUID id);
}
