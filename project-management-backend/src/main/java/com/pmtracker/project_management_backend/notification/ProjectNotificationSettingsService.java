package com.pmtracker.project_management_backend.notification;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.notification.dto.ProjectNotificationSettingsResponse;
import com.pmtracker.project_management_backend.notification.dto.UpdateProjectNotificationSettingsRequest;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * «Следить за проектом / отписаться» (4.16): режим уведомлений одного человека по одному
 * проекту.
 * <p>
 * Прав нужно ровно членство — как у звезды проекта и сохранённых представлений, и по той же
 * причине: это настройка того, кто смотрит, а не изменение проекта. Отсюда же два следствия.
 * Наблюдателю (VIEWER) она доступна наравне со всеми — как раз ему она нужнее прочих, потому
 * что «числюсь ради одной страницы» это именно про него. И архив её не запрещает
 * ({@code requireMembership} вместо {@code requireWriteRole}): архивный проект не меняется,
 * но письма про него человеку всё ещё могли приходить до архивации, и запретить их выключить
 * значило бы сделать из архива ловушку.
 */
@Service
public class ProjectNotificationSettingsService {

    private final ProjectNotificationSettingsRepository settingsRepository;
    private final ProjectAccessService projectAccessService;

    public ProjectNotificationSettingsService(ProjectNotificationSettingsRepository settingsRepository,
                                              ProjectAccessService projectAccessService) {
        this.settingsRepository = settingsRepository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional(readOnly = true)
    public ProjectNotificationSettingsResponse get(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);
        return new ProjectNotificationSettingsResponse(modeOf(projectId, currentUser.getId()));
    }

    /**
     * Возврат к режиму по умолчанию удаляет строку, а не переписывает её в PARTICIPATING.
     * <p>
     * Оба варианта дали бы одинаковое поведение, но храниться должно только то, что человек
     * действительно решил. Строка «PARTICIPATING» — это запись о том, что настройку вернули
     * как было, то есть о несобытии; она ничем не отличалась бы по смыслу от её отсутствия,
     * зато таблица со временем набрала бы по строке на каждого участника каждого проекта, и
     * запрос наблюдателей (см. {@code findWatchers}) читал бы их все ради нескольких.
     */
    @Transactional
    public ProjectNotificationSettingsResponse update(User currentUser, UUID projectId,
                                                      UpdateProjectNotificationSettingsRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        if (request.mode() == ProjectNotificationMode.PARTICIPATING) {
            settingsRepository.deleteByProjectIdAndUserId(projectId, currentUser.getId());
            return new ProjectNotificationSettingsResponse(ProjectNotificationMode.PARTICIPATING);
        }

        ProjectNotificationSettings settings = settingsRepository
                .findByProjectIdAndUserId(projectId, currentUser.getId())
                .orElseGet(() -> {
                    ProjectNotificationSettings fresh = new ProjectNotificationSettings();
                    fresh.setProject(project);
                    fresh.setUser(currentUser);
                    return fresh;
                });
        settings.setMode(request.mode());
        settingsRepository.save(settings);
        return new ProjectNotificationSettingsResponse(settings.getMode());
    }

    /**
     * Режим получателя по проекту; PARTICIPATING, если строки нет.
     * <p>
     * Внутренний API для {@link NotificationService} — прав не проверяет: получатель
     * уведомления определяется доменной логикой, а не запросом пользователя.
     */
    public ProjectNotificationMode modeOf(UUID projectId, UUID userId) {
        return settingsRepository.findModeByProjectIdAndUserId(projectId, userId)
                .orElse(ProjectNotificationMode.PARTICIPATING);
    }

    /**
     * Участника исключили (или он вышел) — настройка уезжает вместе с членством.
     * <p>
     * Оставлять её «на случай, если вернётся» нельзя: это была бы тихо восстановившаяся
     * при повторном приглашении подписка на проект, о которой человек уже не помнит.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void forgetMember(UUID projectId, UUID userId) {
        settingsRepository.deleteByProjectIdAndUserId(projectId, userId);
    }
}
