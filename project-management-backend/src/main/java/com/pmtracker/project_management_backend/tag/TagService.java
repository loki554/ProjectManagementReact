package com.pmtracker.project_management_backend.tag;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.DuplicateTagNameException;
import com.pmtracker.project_management_backend.common.exception.TagNotFoundException;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.tag.dto.CreateTagRequest;
import com.pmtracker.project_management_backend.tag.dto.TagResponse;
import com.pmtracker.project_management_backend.tag.dto.UpdateTagRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TagService {

    private final TagRepository tagRepository;
    private final ProjectAccessService projectAccessService;

    public TagService(TagRepository tagRepository, ProjectAccessService projectAccessService) {
        this.tagRepository = tagRepository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional
    public TagResponse create(User currentUser, UUID projectId, CreateTagRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.OWNER);

        if (tagRepository.existsByProjectIdAndName(projectId, request.name())) {
            throw new DuplicateTagNameException();
        }

        Tag tag = new Tag();
        tag.setProject(project);
        tag.setName(request.name());
        tag.setColor(request.color());
        tag.setCreatedBy(currentUser);
        tagRepository.save(tag);
        return TagResponse.from(tag);
    }

    @Transactional(readOnly = true)
    public List<TagResponse> list(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        return tagRepository.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(TagResponse::from)
                .toList();
    }

    @Transactional
    public TagResponse update(User currentUser, UUID tagId, UpdateTagRequest request) {
        Tag tag = findTagOrThrow(tagId);
        UUID projectId = tag.getProject().getId();
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.OWNER);

        if (!tag.getName().equals(request.name()) && tagRepository.existsByProjectIdAndName(projectId, request.name())) {
            throw new DuplicateTagNameException();
        }

        tag.setName(request.name());
        tag.setColor(request.color());
        tagRepository.save(tag);
        return TagResponse.from(tag);
    }

    @Transactional
    public void delete(User currentUser, UUID tagId) {
        Tag tag = findTagOrThrow(tagId);
        ProjectMember membership = projectAccessService.requireMembership(tag.getProject().getId(), currentUser);
        projectAccessService.requireRole(membership, ProjectRole.OWNER);

        tagRepository.delete(tag);
    }

    private Tag findTagOrThrow(UUID tagId) {
        return tagRepository.findById(tagId).orElseThrow(TagNotFoundException::new);
    }
}
