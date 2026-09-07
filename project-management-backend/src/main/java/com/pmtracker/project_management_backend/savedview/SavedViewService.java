package com.pmtracker.project_management_backend.savedview;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.category.Category;
import com.pmtracker.project_management_backend.category.CategoryRepository;
import com.pmtracker.project_management_backend.common.exception.AssigneeNotProjectMemberException;
import com.pmtracker.project_management_backend.common.exception.CategoryNotFoundException;
import com.pmtracker.project_management_backend.common.exception.DuplicateSavedViewNameException;
import com.pmtracker.project_management_backend.common.exception.SavedViewNotFoundException;
import com.pmtracker.project_management_backend.common.exception.SprintNotFoundException;
import com.pmtracker.project_management_backend.common.exception.TagNotFoundException;
import com.pmtracker.project_management_backend.common.exception.TagProjectMismatchException;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectMember;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import com.pmtracker.project_management_backend.sprint.Sprint;
import com.pmtracker.project_management_backend.sprint.SprintRepository;
import com.pmtracker.project_management_backend.savedview.dto.SavedViewRequest;
import com.pmtracker.project_management_backend.savedview.dto.SavedViewResponse;
import com.pmtracker.project_management_backend.tag.Tag;
import com.pmtracker.project_management_backend.tag.TagRepository;
import com.pmtracker.project_management_backend.task.TaskSortKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Сохранённые представления списка задач (4.7).
 *
 * <p>Представление персональное: и список, и правка, и удаление работают только со своими.
 * Права проекта проверяются ровно один раз — на членство: сохранённый фильтр ничего не
 * меняет в проекте и никому, кроме автора, не виден, поэтому VIEWER заводит свои
 * представления наравне с OWNER. Ограничивать это ролью значило бы отобрать закладки у
 * единственной роли, которая только и делает, что читает списки.
 */
@Service
public class SavedViewService {

    private final SavedViewRepository savedViewRepository;
    private final ProjectAccessService projectAccessService;
    private final ProjectMemberRepository projectMemberRepository;
    private final TagRepository tagRepository;
    private final CategoryRepository categoryRepository;
    private final SprintRepository sprintRepository;

    public SavedViewService(SavedViewRepository savedViewRepository,
                            ProjectAccessService projectAccessService,
                            ProjectMemberRepository projectMemberRepository,
                            TagRepository tagRepository,
                            CategoryRepository categoryRepository,
                            SprintRepository sprintRepository) {
        this.savedViewRepository = savedViewRepository;
        this.projectAccessService = projectAccessService;
        this.projectMemberRepository = projectMemberRepository;
        this.tagRepository = tagRepository;
        this.categoryRepository = categoryRepository;
        this.sprintRepository = sprintRepository;
    }

    @Transactional(readOnly = true)
    public List<SavedViewResponse> list(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        return savedViewRepository.findOwnedByProject(projectId, currentUser.getId()).stream()
                .map(SavedViewResponse::from)
                .toList();
    }

    @Transactional
    public SavedViewResponse create(User currentUser, UUID projectId, SavedViewRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        String name = normalizeName(request.name());
        if (savedViewRepository.existsByProjectIdAndOwnerIdAndName(projectId, currentUser.getId(), name)) {
            throw new DuplicateSavedViewNameException();
        }

        SavedView view = new SavedView();
        view.setProject(project);
        view.setOwner(currentUser);
        view.setName(name);
        applyFilters(view, projectId, request);
        return SavedViewResponse.from(savedViewRepository.save(view));
    }

    @Transactional
    public SavedViewResponse update(User currentUser, UUID viewId, SavedViewRequest request) {
        SavedView view = findOwnOrThrow(currentUser, viewId);
        UUID projectId = view.getProject().getId();
        // Членство перепроверяется и здесь: представление живёт дольше, чем участие в
        // проекте, и человек, из проекта исключённый, не должен продолжать разрешать через
        // него чужие тэги и чужих исполнителей.
        projectAccessService.requireMembership(projectId, currentUser);

        String name = normalizeName(request.name());
        if (!view.getName().equals(name)
                && savedViewRepository.existsByProjectIdAndOwnerIdAndName(projectId, currentUser.getId(), name)) {
            throw new DuplicateSavedViewNameException();
        }

        view.setName(name);
        applyFilters(view, projectId, request);
        return SavedViewResponse.from(savedViewRepository.save(view));
    }

    @Transactional
    public void delete(User currentUser, UUID viewId) {
        savedViewRepository.delete(findOwnOrThrow(currentUser, viewId));
    }

    /**
     * Чужое представление — это 404, а не 403. Отказ по правам сообщил бы, что представление
     * с таким id существует и принадлежит кому-то ещё, а личный набор фильтров соседа —
     * не то, о существовании чего стоит рассказывать по перебору id.
     */
    private SavedView findOwnOrThrow(User currentUser, UUID viewId) {
        SavedView view = savedViewRepository.findById(viewId).orElseThrow(SavedViewNotFoundException::new);
        if (!view.getOwner().getId().equals(currentUser.getId())) {
            throw new SavedViewNotFoundException();
        }
        return view;
    }

    /**
     * Переносит фильтры из запроса в сущность целиком — включая «сбросить в пустое».
     * Обновление представления это всегда «запомни то, что сейчас на экране», поэтому поле,
     * которого в запросе нет, означает снятый фильтр, а не «оставить как было».
     *
     * <p>Ссылки проверяются на принадлежность проекту теми же исключениями, что и при
     * сохранении задачи: сохранённый фильтр по тэгу из чужого проекта — это представление,
     * которое всегда пусто, и узнать об этом лучше при сохранении.
     */
    private void applyFilters(SavedView view, UUID projectId, SavedViewRequest request) {
        view.setSearch(normalizeSearch(request.search()));
        view.setStatus(request.status());
        view.setAssignedToMe(request.assignedToMe());
        view.setUnassigned(request.unassigned());
        // Флаги сильнее значения — тот же приоритет, что на проводе и в фильтрах списка,
        // поэтому «мои» и «без исполнителя» затирают конкретного исполнителя, а не
        // сосуществуют с ним в базе.
        view.setAssignee(request.assignedToMe() || request.unassigned()
                ? null
                : resolveAssignee(projectId, request.assigneeId()));
        view.setTag(resolveTag(projectId, request.tagId()));
        view.setUncategorized(request.uncategorized());
        view.setCategory(request.uncategorized() ? null : resolveCategory(projectId, request.categoryId()));
        view.setNoSprint(request.noSprint());
        view.setSprint(request.noSprint() ? null : resolveSprint(projectId, request.sprintId()));
        view.setDue(request.due());
        view.setSort(request.sort() != null ? request.sort() : TaskSortKey.NUMBER);
        view.setDescending(request.descending());
    }

    private User resolveAssignee(UUID projectId, UUID assigneeId) {
        if (assigneeId == null) {
            return null;
        }
        ProjectMember membership = projectMemberRepository.findByProjectIdAndUserId(projectId, assigneeId)
                .orElseThrow(AssigneeNotProjectMemberException::new);
        return membership.getUser();
    }

    private Tag resolveTag(UUID projectId, UUID tagId) {
        if (tagId == null) {
            return null;
        }
        Tag tag = tagRepository.findById(tagId).orElseThrow(TagNotFoundException::new);
        if (!tag.getProject().getId().equals(projectId)) {
            throw new TagProjectMismatchException();
        }
        return tag;
    }

    private Category resolveCategory(UUID projectId, UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoryRepository.findById(categoryId).orElseThrow(CategoryNotFoundException::new);
        if (!category.getProject().getId().equals(projectId)) {
            // Отдельного исключения на «категория из чужого проекта» в проекте нет, а заводить
            // его ради одного места незачем: для того, кто её не видит, «не та категория» и
            // «нет такой категории» — одно и то же.
            throw new CategoryNotFoundException();
        }
        return category;
    }

    private Sprint resolveSprint(UUID projectId, UUID sprintId) {
        if (sprintId == null) {
            return null;
        }
        Sprint sprint = sprintRepository.findById(sprintId).orElseThrow(SprintNotFoundException::new);
        if (!sprint.getProject().getId().equals(projectId)) {
            // Как и с категорией выше: для того, кто спринт не видит, «не тот проект» и
            // «нет такого спринта» — одно и то же.
            throw new SprintNotFoundException();
        }
        return sprint;
    }

    private static String normalizeName(String name) {
        return name.trim();
    }

    // Пустая строка в поиске — это «фильтр не задан», а не поиск по пустой подстроке
    // (тот же приём, что в CategoryService.normalizeName).
    private static String normalizeSearch(String search) {
        if (search == null) {
            return null;
        }
        String trimmed = search.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
