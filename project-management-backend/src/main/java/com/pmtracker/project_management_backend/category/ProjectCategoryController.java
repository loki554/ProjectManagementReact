package com.pmtracker.project_management_backend.category;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.category.dto.CategoryResponse;
import com.pmtracker.project_management_backend.category.dto.CreateCategoryRequest;
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
@RequestMapping("/api/projects/{projectId}/categories")
@Tag(name = "Categories", description = "Справочник категорий задач проекта")
public class ProjectCategoryController {

    private final CategoryService categoryService;

    public ProjectCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PostMapping
    @Operation(summary = "Создать категорию проекта",
            description = "Только OWNER; имя уникально в рамках проекта. Обычный участник заводит категорию "
                    + "не здесь, а свободным вводом в форме задачи (создаётся на лету при сохранении)")
    public ResponseEntity<CategoryResponse> create(@AuthenticationPrincipal User currentUser,
                                                     @PathVariable UUID projectId,
                                                     @Valid @RequestBody CreateCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(currentUser, projectId, request));
    }

    @GetMapping
    @Operation(summary = "Список категорий проекта",
            description = "Доступно любому участнику проекта, включая VIEWER; отдаёт счётчик задач по каждой категории")
    public ResponseEntity<List<CategoryResponse>> list(@AuthenticationPrincipal User currentUser,
                                                          @PathVariable UUID projectId) {
        return ResponseEntity.ok(categoryService.list(currentUser, projectId));
    }
}
