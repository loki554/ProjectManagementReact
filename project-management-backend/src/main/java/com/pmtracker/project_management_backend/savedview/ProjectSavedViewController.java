package com.pmtracker.project_management_backend.savedview;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.savedview.dto.SavedViewRequest;
import com.pmtracker.project_management_backend.savedview.dto.SavedViewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/views")
@Tag(name = "Saved views", description = "Личные сохранённые представления списка задач проекта")
public class ProjectSavedViewController {

    private final SavedViewService savedViewService;

    public ProjectSavedViewController(SavedViewService savedViewService) {
        this.savedViewService = savedViewService;
    }

    @GetMapping
    @Operation(summary = "Мои представления в проекте",
            description = "Только свои: представление персональное, чужие не видны никому, включая OWNER. "
                    + "Доступно любому участнику проекта, включая VIEWER")
    public ResponseEntity<List<SavedViewResponse>> list(@AuthenticationPrincipal User currentUser,
                                                          @PathVariable UUID projectId) {
        return ResponseEntity.ok(savedViewService.list(currentUser, projectId));
    }

    @PostMapping
    @Operation(summary = "Сохранить текущие фильтры как представление",
            description = "Имя уникально среди своих представлений в этом проекте (у соседа может быть "
                    + "такое же). Тэг, категория и исполнитель проверяются на принадлежность проекту. "
                    + "assignedToMe сохраняет «задачи того, кто смотрит», а не конкретного человека — "
                    + "поэтому такое представление у каждого показывает его собственные задачи")
    public ResponseEntity<SavedViewResponse> create(@AuthenticationPrincipal User currentUser,
                                                      @PathVariable UUID projectId,
                                                      @Valid @RequestBody SavedViewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(savedViewService.create(currentUser, projectId, request));
    }
}
