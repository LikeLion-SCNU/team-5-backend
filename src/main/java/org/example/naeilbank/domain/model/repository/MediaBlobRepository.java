package org.example.naeilbank.domain.model.repository;

import org.example.naeilbank.domain.model.entity.MediaBlob;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MediaBlobRepository extends Repository<MediaBlob, UUID> {
    MediaBlob saveAndFlush(MediaBlob mediaBlob);

    Optional<MediaBlob> findByIdAndUserIdAndStatus(
            UUID id,
            UUID userId,
            MediaBlob.Status status
    );

    Optional<MediaBlob> findByIdAndUserIdAndPurposeAndStatus(
            UUID id,
            UUID userId,
            MediaBlob.Purpose purpose,
            MediaBlob.Status status
    );

    @Query("""
            select m.id as id,
                   m.purpose as purpose,
                   m.contentType as contentType,
                   m.sizeBytes as sizeBytes,
                   m.sha256 as sha256,
                   m.createdAt as createdAt
            from MediaBlob m
            where m.id = :id
              and m.userId = :userId
              and m.status = :status
            """)
    Optional<MediaBlobMetadataView> findMetadata(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("status") MediaBlob.Status status
    );

    @Query("""
            select m.id as id,
                   m.purpose as purpose,
                   m.contentType as contentType,
                   m.sizeBytes as sizeBytes,
                   m.sha256 as sha256,
                   m.createdAt as createdAt
            from MediaBlob m
            where m.userId = :userId
              and m.purpose = :purpose
              and m.sha256 = :sha256
              and m.status = :status
            """)
    Optional<MediaBlobMetadataView> findMetadataByDigest(
            @Param("userId") UUID userId,
            @Param("purpose") MediaBlob.Purpose purpose,
            @Param("sha256") String sha256,
            @Param("status") MediaBlob.Status status
    );

    void delete(MediaBlob mediaBlob);

    void flush();
}
