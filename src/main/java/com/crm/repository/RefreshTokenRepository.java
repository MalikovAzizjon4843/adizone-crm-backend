package com.crm.repository;

import com.crm.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenAndIsRevokedFalse(String token);

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    void deleteByToken(String token);

    @Query("SELECT r FROM RefreshToken r WHERE r.expiresAt < :now")
    List<RefreshToken> findExpiredTokens(@Param("now") LocalDateTime now);
}
