package com.pmtracker.project_management_backend.wiki;

import com.pmtracker.project_management_backend.activity.ActivityService;
import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.ConcurrentModificationConflictException;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.wiki.dto.UpdateWikiRequest;
import com.pmtracker.project_management_backend.wiki.dto.WikiResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class WikiService {

    private final ProjectWikiRepository projectWikiRepository;
    private final ProjectAccessService projectAccessService;
    private final ActivityService activityService;

    public WikiService(ProjectWikiRepository projectWikiRepository,
                       ProjectAccessService projectAccessService,
                       ActivityService activityService) {
        this.projectWikiRepository = projectWikiRepository;
        this.projectAccessService = projectAccessService;
        this.activityService = activityService;
    }

    @Transactional(readOnly = true)
    public WikiResponse get(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);
        return projectWikiRepository.findByProjectId(projectId)
                .map(WikiResponse::from)
                .orElseGet(WikiResponse::empty);
    }

    // Upsert: строка вики создаётся лениво при первом сохранении. Читать могут все
    // участники (включая VIEWER), редактировать — MEMBER и выше.
    @Transactional
    public WikiResponse update(User currentUser, UUID projectId, UpdateWikiRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(role, ProjectRole.MEMBER);

        Optional<ProjectWiki> existing = projectWikiRepository.findByProjectId(projectId);

        // Версия, которую клиент видел при открытии редактора (3.4). У проекта без вики это
        // NO_WIKI_VERSION, а не 0, — иначе тот, кто начал писать страницу с нуля и сохранил
        // её вторым, затёр бы страницу первого: у только что созданной строки версия 0.
        long currentVersion = existing.map(ProjectWiki::getVersion).orElse(WikiResponse.NO_WIKI_VERSION);
        if (request.version() == null || request.version() != currentVersion) {
            throw new ConcurrentModificationConflictException();
        }

        ProjectWiki wiki = existing.orElseGet(() -> {
            ProjectWiki created = new ProjectWiki();
            created.setProject(project);
            return created;
        });
        wiki.setContent(request.content());
        wiki.setUpdatedBy(currentUser);
        // saveAndFlush, а не save: версия увеличивается на flush, а ответ собирается прямо
        // здесь — без явного сброса клиент получил бы ту же версию, что и прислал, и его
        // следующее сохранение упёрлось бы в собственный конфликт.
        WikiResponse response = WikiResponse.from(projectWikiRepository.saveAndFlush(wiki));
        activityService.record(project, currentUser, "wiki_updated", null, Map.of());
        return response;
    }
}
