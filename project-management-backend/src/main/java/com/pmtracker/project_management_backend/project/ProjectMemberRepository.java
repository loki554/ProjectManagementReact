package com.pmtracker.project_management_backend.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    /**
     * Список проектов пользователя (ProjectService.listForUser). join fetch, а не derived-запрос:
     * ProjectMember.project и Project.createdBy — это @ManyToOne, то есть EAGER по умолчанию,
     * но Hibernate не подставляет их в HQL сам, а догружает отдельным SELECT на каждую строку.
     * Без fetch join список из N проектов стоил 1 + 2N запросов, при том что ProjectResponse.from
     * читает у проекта ровно те поля, которые уже лежат в join'е (createdBy нужен только ради id).
     */
    @Query("""
            select m from ProjectMember m
            join fetch m.project p
            join fetch p.createdBy
            where m.user.id = :userId
            order by p.createdAt desc
            """)
    List<ProjectMember> findByUserIdWithProject(UUID userId);

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    /**
     * Только роль — для проверки доступа (ProjectAccessService), которая выполняется почти
     * на каждом запросе и от строки участника больше ничего не берёт. Заодно это то, что
     * можно безопасно положить в кэш (3.10): enum неизменяем, в отличие от сущности,
     * которую пришлось бы отдавать наружу отсоединённой и общей на всех.
     */
    @Query("select m.role from ProjectMember m where m.project.id = :projectId and m.user.id = :userId")
    Optional<ProjectRole> findRoleByProjectIdAndUserId(UUID projectId, UUID userId);

    /**
     * Список участников проекта (ProjectMemberService.list). Тот же N+1, что и выше, только
     * с другой стороны связи: MemberResponse.from разворачивает member.getUser() на каждой
     * строке, а без fetch join это отдельный SELECT на участника.
     */
    @Query("""
            select m from ProjectMember m
            join fetch m.user
            where m.project.id = :projectId
            order by m.joinedAt asc
            """)
    List<ProjectMember> findByProjectIdWithUser(UUID projectId);

    long countByProjectIdAndRole(UUID projectId, ProjectRole role);
}
