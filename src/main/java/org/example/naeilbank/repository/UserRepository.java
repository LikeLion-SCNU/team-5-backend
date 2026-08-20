package org.example.naeilbank.repository;

import org.example.naeilbank.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByKakaoId(String kakaoId);
    boolean existsByEmail(String email);
    boolean existsByEmailIgnoreCase(String email);

    @Query(value = "select pg_advisory_xact_lock(hashtextextended(cast(:kakaoId as text), 0))", nativeQuery = true)
    void lockKakaoId(@Param("kakaoId") String kakaoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") UUID userId);
}
