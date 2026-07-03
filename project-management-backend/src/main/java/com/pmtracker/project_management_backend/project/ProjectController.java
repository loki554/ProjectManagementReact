package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.project.dto.CreateProjectRequest;
import com.pmtracker.project_management_backend.project.dto.ProjectResponse;
import com.pmtracker.project_management_backend.project.dto.UpdateProjectRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@AuthenticationPrincipal User currentUser,
                                                   @Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(currentUser, request));
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(projectService.listForUser(currentUser));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> get(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        return ResponseEntity.ok(projectService.getById(currentUser, id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(@AuthenticationPrincipal User currentUser,
                                                   @PathVariable UUID id,
                                                   @Valid @RequestBody UpdateProjectRequest request) {
        return ResponseEntity.ok(projectService.update(currentUser, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        projectService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
