package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectNotificationSettingsRepository extends JpaRepository<ProjectNotificationSettings, UUID> {

    Optional<ProjectNotificationSettings> findByProjectIdAndUserId(UUID projectId, UUID userId);

    /**
     * Только режим — для проверки «не выключил ли получатель этот проект», которая идёт на
     * каждое создаваемое уведомление. Тот же приём, что у
     * {@code findRoleByProjectIdAndUserId}: сущность здесь не нужна ни одним полем, а
     * тащить её (вместе с проектом и пользователем на @ManyToOne) ради одного enum'а —
     * лишняя работа на самом частом пути.
     */
    @Query("select s.mode from ProjectNotificationSettings s "
            + "where s.project.id = :projectId and s.user.id = :userId")
    Optional<ProjectNotificationMode> findModeByProjectIdAndUserId(UUID projectId, UUID userId);

    /**
     * Наблюдатели проекта — те, кто попросил присылать всё (4.16).
     *
     * <p>{@code exists} по участникам здесь не перестраховка, а условие корректности.
     * Настройка ставится участником, но членство потом может кончиться — исключили,
     * вышел, — и без этой проверки строка пережила бы членство и продолжала слать
     * уведомления о проекте, в который человек уже не может даже заглянуть. Строка при
     * исключении удаляется явно (см. {@code ProjectMemberService.remove}), но полагаться
     * на то, что все нынешние и будущие пути выхода из проекта об этом вспомнят, для
     * рассылки наружу — слишком дорого.
     */
    @Query("""
            select s.user from ProjectNotificationSettings s
            where s.project.id = :projectId
              and s.mode = com.pmtracker.project_management_backend.notification.ProjectNotificationMode.ALL
              and exists (select 1 from ProjectMember m
                          where m.project.id = :projectId and m.user.id = s.user.id)
            """)
    List<User> findWatchers(UUID projectId);

    void deleteByProjectIdAndUserId(UUID projectId, UUID userId);
}
