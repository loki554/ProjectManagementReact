package com.pmtracker.project_management_backend.tasktemplate;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.category.Category;
import com.pmtracker.project_management_backend.category.CategoryRepository;
import com.pmtracker.project_management_backend.common.exception.CategoryNotFoundException;
import com.pmtracker.project_management_backend.common.exception.DuplicateTaskTemplateNameException;
import com.pmtracker.project_management_backend.common.exception.TagNotFoundException;
import com.pmtracker.project_management_backend.common.exception.TagProjectMismatchException;
import com.pmtracker.project_management_backend.common.exception.TaskTemplateNotFoundException;
import com.pmtracker.project_management_backend.common.exception.TaskTemplateProjectMismatchException;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.tag.Tag;
import com.pmtracker.project_management_backend.tag.TagRepository;
import com.pmtracker.project_management_backend.tasktemplate.dto.TaskTemplateItemRequest;
import com.pmtracker.project_management_backend.tasktemplate.dto.TaskTemplateRequest;
import com.pmtracker.project_management_backend.tasktemplate.dto.TaskTemplateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Шаблоны задач (4.13).
 *
 * <p><b>Про права.</b> Видят шаблоны все участники, включая VIEWER: список шаблонов нужен
 * форме заведения задачи, а спрятать от наблюдателя названия заготовок было бы странно —
 * он видит сами задачи, из них сделанные. Заводят, правят и удаляют — ADMIN и выше.
 *
 * <p>Это отличается от тэгов и категорий (там OWNER), и различие намеренное. Тэг и
 * категория — общий словарь, уже приклеенный к сотням задач: переименование тэга переписывает
 * то, что стоит на карточках, и потому это решение владельца. Шаблон не трогает ничего из
 * уже существующего — он лишь заполняет пустую форму, которую человек тут же и правит
 * глазами. Цена ошибки в нём равна нулю, а цена «сходи найди владельца, чтобы завести
 * заготовку» — та самая ручная работа, ради устранения которой пункт и делался. ADMIN здесь
 * ровно то же, что и у спринтов: роль, которая ведёт проект, не владея им.
 *
 * <p><b>Шаблон и задача связаны только моментом создания.</b> Задача не помнит, из какого
 * шаблона она сделана, и правка шаблона не догоняет уже созданные задачи. Это заготовка, а
 * не наследование: «дописал в шаблон одиннадцатый шаг» не должно менять сорок задач, из
 * которых половина закрыта.
 */
@Service
public class TaskTemplateService {

    private final TaskTemplateRepository taskTemplateRepository;
    private final TaskTemplateItemRepository taskTemplateItemRepository;
    private final ProjectAccessService projectAccessService;
    private final TagRepository tagRepository;
    private final CategoryRepository categoryRepository;

    public TaskTemplateService(TaskTemplateRepository taskTemplateRepository,
                               TaskTemplateItemRepository taskTemplateItemRepository,
                               ProjectAccessService projectAccessService,
                               TagRepository tagRepository,
                               CategoryRepository categoryRepository) {
        this.taskTemplateRepository = taskTemplateRepository;
        this.taskTemplateItemRepository = taskTemplateItemRepository;
        this.projectAccessService = projectAccessService;
        this.tagRepository = tagRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    public List<TaskTemplateResponse> list(User currentUser, UUID projectId) {
        projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);

        Map<UUID, Long> itemCounts = taskTemplateRepository.countItemsByProjectId(projectId).stream()
                .collect(Collectors.toMap(TaskTemplateRepository.TemplateItemCount::getTemplateId,
                        TaskTemplateRepository.TemplateItemCount::getItemCount));

        return taskTemplateRepository.findByProjectId(projectId).stream()
                .map(template -> TaskTemplateResponse.summary(template, itemCounts.getOrDefault(template.getId(), 0L)))
                .toList();
    }

    /** Один шаблон вместе с пунктами — это то, что читает форма заведения задачи. */
    @Transactional(readOnly = true)
    public TaskTemplateResponse get(User currentUser, UUID templateId) {
        TaskTemplate template = findTemplateOrThrow(templateId);
        projectAccessService.requireMembership(template.getProject().getId(), currentUser);
        return TaskTemplateResponse.from(template, loadItems(templateId));
    }

    @Transactional
    public TaskTemplateResponse create(User currentUser, UUID projectId, TaskTemplateRequest request) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        ProjectRole role = projectAccessService.requireMembership(projectId, currentUser);
        projectAccessService.requireWriteRole(project, role, ProjectRole.ADMIN);

        String name = request.name().trim();
        if (taskTemplateRepository.existsByProjectIdAndName(projectId, name)) {
            throw new DuplicateTaskTemplateNameException();
        }

        TaskTemplate template = new TaskTemplate();
        template.setProject(project);
        template.setCreatedBy(currentUser);
        template.setName(name);
        applyFields(template, project, request);
        taskTemplateRepository.save(template);
        List<TaskTemplateItem> items = replaceItems(template, request.items());

        return TaskTemplateResponse.from(template, items);
    }

    @Transactional
    public TaskTemplateResponse update(User currentUser, UUID templateId, TaskTemplateRequest request) {
        TaskTemplate template = findTemplateOrThrow(templateId);
        Project project = template.getProject();
        ProjectRole role = projectAccessService.requireMembership(project.getId(), currentUser);
        projectAccessService.requireWriteRole(project, role, ProjectRole.ADMIN);

        String name = request.name().trim();
        if (!template.getName().equals(name)
                && taskTemplateRepository.existsByProjectIdAndName(project.getId(), name)) {
            throw new DuplicateTaskTemplateNameException();
        }

        template.setName(name);
        applyFields(template, project, request);
        taskTemplateRepository.save(template);
        List<TaskTemplateItem> items = replaceItems(template, request.items());

        return TaskTemplateResponse.from(template, items);
    }

    /**
     * Удаление шаблона. Задачи, заведённые по нему, не трогаются вовсе — они и не помнят о
     * нём: шаблон копируется в момент создания, а не остаётся источником правды.
     */
    @Transactional
    public void delete(User currentUser, UUID templateId) {
        TaskTemplate template = findTemplateOrThrow(templateId);
        Project project = template.getProject();
        ProjectRole role = projectAccessService.requireMembership(project.getId(), currentUser);
        projectAccessService.requireWriteRole(project, role, ProjectRole.ADMIN);
        // Пункты уедут за ним по ON DELETE CASCADE (V33).
        taskTemplateRepository.delete(template);
    }

    /**
     * Шаблон для применения в TaskService: с проверкой, что он из того же проекта, что и
     * задача. Вызывается уже после проверки прав на создание задачи, поэтому своих проверок
     * доступа здесь нет.
     */
    @Transactional(readOnly = true)
    public TaskTemplate requireTemplateOfProject(UUID templateId, UUID projectId) {
        TaskTemplate template = findTemplateOrThrow(templateId);
        if (!template.getProject().getId().equals(projectId)) {
            // Не «не найден»: сообщать, что чужой шаблон существует, незачем — но и делать
            // вид, что запрос корректен, тоже. Тот же выбор, что у тэга из другого проекта.
            throw new TaskTemplateProjectMismatchException();
        }
        return template;
    }

    @Transactional(readOnly = true)
    public List<String> templateItemContents(UUID templateId) {
        return loadItems(templateId).stream().map(TaskTemplateItem::getContent).toList();
    }

    /**
     * Пункты переписываются целиком: старые удаляются, новые вставляются в порядке списка.
     * Сопоставлять присланные пункты с существующими по id и переставлять их было бы
     * дороже во всех смыслах — и в коде, и для того, кто правит форму: у пункта чек-листа
     * нет ничего, что стоило бы сохранить между редакциями (ни автора, ни истории, ни
     * галочки — она появляется только на копии в задаче).
     */
    private List<TaskTemplateItem> replaceItems(TaskTemplate template, List<TaskTemplateItemRequest> requested) {
        taskTemplateItemRepository.deleteByTemplateId(template.getId());
        // flush через сам deleteByTemplateId: derived-delete Spring Data выполняется сразу,
        // поэтому вставка ниже не столкнётся со старыми строками.
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        int position = 0;
        List<TaskTemplateItem> items = new ArrayList<>(requested.size());
        for (TaskTemplateItemRequest itemRequest : requested) {
            TaskTemplateItem item = new TaskTemplateItem();
            item.setTemplate(template);
            item.setContent(itemRequest.content().trim());
            item.setPosition(position++);
            items.add(taskTemplateItemRepository.save(item));
        }
        return items;
    }

    private void applyFields(TaskTemplate template, Project project, TaskTemplateRequest request) {
        template.setTitle(blankToNull(request.title()));
        template.setDescription(blankToNull(request.description()));
        template.setUrgency(request.urgency());
        template.setTag(resolveTag(project, request.tagId()));
        template.setCategory(resolveCategory(project, request.categoryId()));
    }

    private Tag resolveTag(Project project, UUID tagId) {
        if (tagId == null) {
            return null;
        }
        Tag tag = tagRepository.findById(tagId).orElseThrow(TagNotFoundException::new);
        if (!tag.getProject().getId().equals(project.getId())) {
            throw new TagProjectMismatchException();
        }
        return tag;
    }

    /**
     * Категория здесь выбирается из справочника по id, а не вводится текстом, как в форме
     * задачи (CategoryService.resolveOrCreate). Свободный ввод существует, чтобы не
     * прерывать работу над задачей походом в настройки; шаблон и так заводят в настройках,
     * и заводить оттуда ещё и категории «на лету» значило бы плодить их опечатками в месте,
     * где спешить некуда.
     */
    private Category resolveCategory(Project project, UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoryRepository.findById(categoryId).orElseThrow(CategoryNotFoundException::new);
        if (!category.getProject().getId().equals(project.getId())) {
            throw new CategoryNotFoundException();
        }
        return category;
    }

    private List<TaskTemplateItem> loadItems(UUID templateId) {
        return taskTemplateItemRepository.findByTemplateIdOrderByPositionAsc(templateId);
    }

    private TaskTemplate findTemplateOrThrow(UUID templateId) {
        return taskTemplateRepository.findById(templateId).orElseThrow(TaskTemplateNotFoundException::new);
    }

    // Пустая строка из формы — это «поле не задано», а не заголовок из нуля символов
    // (тот же приём, что у категории в CategoryService.normalizeName).
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
