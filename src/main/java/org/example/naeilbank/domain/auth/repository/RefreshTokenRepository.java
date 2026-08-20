package org.example.naeilbank.domain.auth.repository;

import jakarta.persistence.LockModeType;
import org.example.naeilbank.domain.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken token
               set token.revokedAt = :now
             where token.familyId = :familyId
               and token.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken token
               set token.revokedAt = :now
             where token.userId = :userId
               and token.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into refresh_tokens
                (id, user_id, token_hash, family_id, previous_token_hash, expires_at, created_at)
            values
                (:id, :userId, :tokenHash, :familyId, :previousTokenHash, :expiresAt, :createdAt)
            on conflict (token_hash) do nothing
            """, nativeQuery = true)
    int insertIfTokenHashAbsent(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("tokenHash") String tokenHash,
            @Param("familyId") UUID familyId,
            @Param("previousTokenHash") String previousTokenHash,
            @Param("expiresAt") Instant expiresAt,
            @Param("createdAt") Instant createdAt
    );
}
