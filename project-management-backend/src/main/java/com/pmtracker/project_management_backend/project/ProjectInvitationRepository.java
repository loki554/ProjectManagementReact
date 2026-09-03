package com.pmtracker.project_management_backend.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectInvitationRepository extends JpaRepository<ProjectInvitation, UUID> {

    /**
     * Поиск по предъявленной ссылке. join fetch — потому что дальше у приглашения в любом
     * случае спросят проект (его название уходит и в превью, и в письмо), а @ManyToOne
     * здесь LAZY: без fetch это был бы отдельный SELECT на каждую проверку токена.
     */
    @Query("""
            select i from ProjectInvitation i
            join fetch i.project
            where i.tokenHash = :tokenHash
            """)
    Optional<ProjectInvitation> findByTokenHash(@Param("tokenHash") String tokenHash);

    Optional<ProjectInvitation> findByProjectIdAndEmail(UUID projectId, String email);

    /**
     * Непринятые приглашения проекта — для списка в настройках. Просроченные не
     * отфильтровываются: показать «истекло» полезнее, чем молча спрятать (иначе
     * администратор видит только то, что человек не пришёл, и не понимает, почему).
     */
    @Query("""
            select i from ProjectInvitation i
            left join fetch i.invitedBy
            where i.project.id = :projectId
            order by i.createdAt desc
            """)
    List<ProjectInvitation> findByProjectIdWithInviter(@Param("projectId") UUID projectId);

    /**
     * Все живые приглашения на адрес — по одному на проект. Зовётся на подтверждении email
     * (см. ProjectInvitationService.acceptAllPendingFor), поэтому просроченные отсекаются
     * здесь же: принимать их нельзя, а джоб уборки приходит только ночью.
     */
    @Query("""
            select i from ProjectInvitation i
            join fetch i.project
            where i.email = :email and i.expiresAt > :now
            """)
    List<ProjectInvitation> findPendingByEmail(@Param("email") String email, @Param("now") Instant now);

    /** Просроченные приглашения — ночная уборка, см. ProjectInvitationCleanupJob. */
    @Modifying
    @Query("delete from ProjectInvitation i where i.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
