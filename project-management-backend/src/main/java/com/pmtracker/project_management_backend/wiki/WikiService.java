package com.pmtracker.project_management_backend.wiki;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.wiki.dto.UpdateWikiRequest;
import com.pmtracker.project_management_backend.wiki.dto.WikiResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WikiService {

    private final ProjectWikiRepository projectWikiRepository;
    private final ProjectAccessService projectAccessService;

    public WikiService(ProjectWikiRepository projectWikiRepository,
                       ProjectAccessService projectAccessService) {
        this.projectWikiRepository = projectWikiRepository;
        this.projectAccessService = projectAccessService;
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
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.MEMBER);

        ProjectWiki wiki = projectWikiRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    ProjectWiki created = new ProjectWiki();
                    created.setProject(project);
                    return created;
                });
        wiki.setContent(request.content());
        wiki.setUpdatedBy(currentUser);
        return WikiResponse.from(projectWikiRepository.save(wiki));
    }
}
