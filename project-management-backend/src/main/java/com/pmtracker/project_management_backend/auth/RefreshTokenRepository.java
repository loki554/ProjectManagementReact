package com.pmtracker.project_management_backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Гасит все живые refresh-токены пользователя одним UPDATE — используется, когда
     * обнаружено повторное использование уже отозванного токена (см. AuthService.refresh).
     * Возвращает количество погашенных токенов, чтобы было что написать в лог.
     *
     * flushAutomatically/clearAutomatically: bulk-запрос идёт мимо persistence context,
     * поэтому загруженные RefreshToken-сущности после него протухают, и их надо выкинуть
     * из контекста, чтобы никто случайно не прочитал revoked = false из кеша первого уровня.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revoked = true where t.user.id = :userId and t.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId);
}
