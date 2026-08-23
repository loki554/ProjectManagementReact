package com.pmtracker.project_management_backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByToken(UUID token);

    void deleteByUser(User user);

    /**
     * Удаляет протухшие токены подтверждения (см. TokenCleanupJob). Без грейс-периода:
     * истёкший токен уже ничего не открывает, а использованный удаляется сразу при
     * подтверждении — то есть всё, что здесь остаётся, это чистый мусор.
     */
    @Modifying
    @Query("delete from EmailVerificationToken t where t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
