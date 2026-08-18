package com.pmtracker.project_management_backend.category;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.category.dto.CategoryResponse;
import com.pmtracker.project_management_backend.category.dto.CreateCategoryRequest;
import com.pmtracker.project_management_backend.category.dto.UpdateCategoryRequest;
import com.pmtracker.project_management_backend.common.exception.CategoryNotFoundException;
import com.pmtracker.project_management_backend.common.exception.DuplicateCategoryNameException;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProjectAccessService projectAccessService;

    public CategoryService(CategoryRepository categoryRepository, ProjectAccessService projectAccessService) {
        this.categoryRepository = categoryRepository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional
    public CategoryResponse create(User currentUser, UUID projectId, CreateCategoryRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.OWNER);

        String name = normalizeName(request.name());
        if (categoryRepository.existsByProjectIdAndName(projectId, name)) {
            throw new DuplicateCategoryNameException();
        }

        Category category = persist(project, currentUser, name);
        return CategoryResponse.from(category, 0);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        Map<UUID, Long> counts = categoryRepository.countTasksByProjectId(projectId).stream()
                .collect(Collectors.toMap(CategoryRepository.CategoryTaskCount::getCategoryId,
                        CategoryRepository.CategoryTaskCount::getTaskCount));

        return categoryRepository.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(category -> CategoryResponse.from(category, counts.getOrDefault(category.getId(), 0L)))
                .toList();
    }

    @Transactional
    public CategoryResponse update(User currentUser, UUID categoryId, UpdateCategoryRequest request) {
        Category category = findCategoryOrThrow(categoryId);
        UUID projectId = category.getProject().getId();
        ProjectMember membership = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireRole(membership, ProjectRole.OWNER);

        String name = normalizeName(request.name());
        if (!category.getName().equals(name) && categoryRepository.existsByProjectIdAndName(projectId, name)) {
            throw new DuplicateCategoryNameException();
        }

        // Переименование не трогает задачи: они ссылаются на категорию по FK, поэтому новое
        // имя подхватывается везде само (в отличие от свободного текста в V17, где пришлось бы
        // делать массовый UPDATE по всем задачам проекта).
        category.setName(name);
        categoryRepository.save(category);
        return CategoryResponse.from(category, taskCount(categoryId, projectId));
    }

    @Transactional
    public void delete(User currentUser, UUID categoryId) {
        Category category = findCategoryOrThrow(categoryId);
        ProjectMember membership = projectAccessService.requireMembership(category.getProject().getId(), currentUser);
        projectAccessService.requireRole(membership, ProjectRole.OWNER);

        // У задач, использовавших категорию, category становится null — как при удалении тэга
        // (ON DELETE SET NULL в V18), сами задачи не трогаем.
        categoryRepository.delete(category);
    }

    /**
     * Категория по имени для сохранения задачи: существующую находим, отсутствующую создаём.
     * Это то, что сохраняет свободный ввод в форме задачи после переезда на справочник —
     * поэтому здесь, в отличие от create(), достаточно прав на редактирование задачи (их уже
     * проверил вызывающий TaskService) и не требуется роль OWNER.
     */
    @Transactional
    public Category resolveOrCreate(Project project, User currentUser, String rawName) {
        String name = normalizeName(rawName);
        if (name == null) {
            return null;
        }
        return categoryRepository.findByProjectIdAndName(project.getId(), name)
                .orElseGet(() -> persist(project, currentUser, name));
    }

    private Category persist(Project project, User currentUser, String name) {
        Category category = new Category();
        category.setProject(project);
        category.setName(name);
        category.setCreatedBy(currentUser);
        return categoryRepository.save(category);
    }

    private long taskCount(UUID categoryId, UUID projectId) {
        return categoryRepository.countTasksByProjectId(projectId).stream()
                .filter(row -> row.getCategoryId().equals(categoryId))
                .mapToLong(CategoryRepository.CategoryTaskCount::getTaskCount)
                .findFirst()
                .orElse(0L);
    }

    // Пустая строка с фронтенда — это "категория не задана", а не категория с пустым именем
    // (тот же приём, что был у свободного текста в V17).
    private static String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Category findCategoryOrThrow(UUID categoryId) {
        return categoryRepository.findById(categoryId).orElseThrow(CategoryNotFoundException::new);
    }
}
