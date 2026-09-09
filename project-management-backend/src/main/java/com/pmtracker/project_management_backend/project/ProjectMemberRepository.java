package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
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

    /**
     * Участники проекта по списку никнеймов — разбор @упоминаний в комментарии (4.5).
     * <p>
     * Фильтр по проекту здесь и есть проверка прав: упоминание не должно быть способом
     * прислать уведомление постороннему. Человек, которого в проекте нет, просто не найдётся
     * — и с точки зрения автора комментария это правильно и тихо: он написал никнейм, который
     * ничего не значит в этом треде, ровно как опечатался бы в нём.
     * <p>
     * Никнеймы приходят уже нормализованными ({@code MentionParser}), а в базе они лежат в
     * нижнем регистре (V28) — поэтому сравнение прямое, без {@code lower()}: функция вокруг
     * колонки увела бы запрос мимо уникального индекса по {@code users.username}.
     */
    @Query("""
            select m.user from ProjectMember m
            where m.project.id = :projectId and m.user.username in :usernames
            """)
    List<User> findUsersByProjectIdAndUsernameIn(UUID projectId, Collection<String> usernames);

    /**
     * Кто из перечисленных состоит в проекте — адресаты живого обновления (4.15).
     * <p>
     * Список на входе, а не «все участники проекта», потому что спрашивают здесь не «кому
     * это видно» вообще, а «кому это видно из тех, у кого сейчас открыта вкладка». Первых
     * может быть сколько угодно, вторых — единицы; фильтр по {@code IN} превращает рассылку
     * в один индексный запрос по (project_id, user_id) вместо выгрузки всего состава ради
     * пересечения с горсткой id.
     */
    @Query("select m.user.id from ProjectMember m where m.project.id = :projectId and m.user.id in :userIds")
    List<UUID> findUserIdsByProjectIdAndUserIdIn(UUID projectId, Collection<UUID> userIds);

    long countByProjectIdAndRole(UUID projectId, ProjectRole role);
}
