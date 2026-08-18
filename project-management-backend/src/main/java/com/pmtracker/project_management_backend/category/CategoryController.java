package com.pmtracker.project_management_backend.category;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.category.dto.CategoryResponse;
import com.pmtracker.project_management_backend.category.dto.UpdateCategoryRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/categories/{id}")
@Tag(name = "Categories", description = "Справочник категорий задач проекта")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PatchMapping
    @Operation(summary = "Переименовать категорию",
            description = "Только OWNER; задачи ссылаются на категорию по id, поэтому новое имя подхватывается везде само")
    public ResponseEntity<CategoryResponse> update(@AuthenticationPrincipal User currentUser,
                                                     @PathVariable UUID id,
                                                     @Valid @RequestBody UpdateCategoryRequest request) {
        return ResponseEntity.ok(categoryService.update(currentUser, id, request));
    }

    @DeleteMapping
    @Operation(summary = "Удалить категорию", description = "Только OWNER; у задач, использовавших категорию, category становится null")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User currentUser, @PathVariable UUID id) {
        categoryService.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
