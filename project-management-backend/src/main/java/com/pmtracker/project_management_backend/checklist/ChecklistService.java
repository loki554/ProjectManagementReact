package com.pmtracker.project_management_backend.checklist;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.checklist.dto.ChecklistItemResponse;
import com.pmtracker.project_management_backend.checklist.dto.CreateChecklistItemRequest;
import com.pmtracker.project_management_backend.checklist.dto.UpdateChecklistItemRequest;
import com.pmtracker.project_management_backend.common.exception.ChecklistItemNotFoundException;
import com.pmtracker.project_management_backend.common.exception.TaskNotFoundException;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.project.ProjectRole;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Чек-листы задач (4.13).
 *
 * <p><b>Про права.</b> Читают чек-лист все участники, включая VIEWER, — это часть задачи, а
 * задачу видно всем. Пишут (добавить, переименовать, отметить, удалить) — MEMBER и выше,
 * ровно как правят саму задачу. Отдельного права «только отметить галочку» нет намеренно:
 * VIEWER — это роль, которая смотрит, и галочка в чужом чек-листе меняет состояние работы
 * так же, как смена статуса задачи, которую наблюдателю тоже не дают.
 *
 * <p><b>Чего здесь нет.</b> Перестановки пунктов: порядок задаётся при написании, а
 * переставлять пять строк перетаскиванием — это интерфейс, стоящий дороже задачи, которую
 * он решает (пункт всегда можно переписать). Ленты активности на галочку: чек-лист
 * отмечают десятками за день, и лента проекта превратилась бы в поток «отметил пункт»,
 * похоронив под собой события, ради которых её читают. Уведомлений — по той же причине.
 */
@Service
public class ChecklistService {

    private final ChecklistItemRepository checklistItemRepository;
    private final TaskRepository taskRepository;
    private final ProjectAccessService projectAccessService;

    public ChecklistService(ChecklistItemRepository checklistItemRepository,
                            TaskRepository taskRepository,
                            ProjectAccessService projectAccessService) {
        this.checklistItemRepository = checklistItemRepository;
        this.taskRepository = taskRepository;
        this.projectAccessService = projectAccessService;
    }

    @Transactional(readOnly = true)
    public List<ChecklistItemResponse> list(User currentUser, UUID taskId) {
        Task task = findTaskOrThrow(taskId);
        projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        return checklistItemRepository.findByTaskIdOrderByPositionAsc(taskId).stream()
                .map(ChecklistItemResponse::from)
                .toList();
    }

    @Transactional
    public ChecklistItemResponse create(User currentUser, UUID taskId, CreateChecklistItemRequest request) {
        Task task = findTaskOrThrow(taskId);
        ProjectRole role = projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        projectAccessService.requireWriteRole(task.getProject(), role, ProjectRole.MEMBER);

        ChecklistItem item = new ChecklistItem();
        item.setTask(task);
        item.setContent(request.content().trim());
        item.setPosition(checklistItemRepository.findMaxPosition(taskId) + 1);
        checklistItemRepository.save(item);
        return ChecklistItemResponse.from(item);
    }

    /**
     * Правка пункта: текст, галочка или и то и другое. null-поле означает «не трогать» —
     * см. UpdateChecklistItemRequest о том, почему здесь, в отличие от остальных форм
     * трекера, запрос частичный.
     */
    @Transactional
    public ChecklistItemResponse update(User currentUser, UUID itemId, UpdateChecklistItemRequest request) {
        ChecklistItem item = findItemOrThrow(itemId);
        Task task = item.getTask();
        ProjectRole role = projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        projectAccessService.requireWriteRole(task.getProject(), role, ProjectRole.MEMBER);

        if (request.content() != null) {
            item.setContent(request.content().trim());
        }
        if (request.done() != null) {
            item.setDone(request.done());
        }
        checklistItemRepository.save(item);
        return ChecklistItemResponse.from(item);
    }

    /**
     * Удаление пункта. Позиции соседей не пересчитываются: они нужны только для порядка
     * сортировки, а дырка в нумерации (0, 1, 3) сортировке не мешает — переписывать ради
     * неё весь список значило бы делать лишний UPDATE на каждое удаление галочки.
     */
    @Transactional
    public void delete(User currentUser, UUID itemId) {
        ChecklistItem item = findItemOrThrow(itemId);
        Task task = item.getTask();
        ProjectRole role = projectAccessService.requireMembership(task.getProject().getId(), currentUser);
        projectAccessService.requireWriteRole(task.getProject(), role, ProjectRole.MEMBER);
        checklistItemRepository.delete(item);
    }

    /**
     * Скопировать пункты шаблона в только что созданную задачу (4.13). Вызывается из
     * TaskService при создании задачи по шаблону — не через HTTP, поэтому права здесь не
     * проверяются повторно: их уже проверил тот, кто создавал задачу.
     */
    @Transactional
    public void copyFromTemplate(Task task, List<String> contents) {
        int position = 0;
        for (String content : contents) {
            ChecklistItem item = new ChecklistItem();
            item.setTask(task);
            item.setContent(content);
            item.setPosition(position++);
            checklistItemRepository.save(item);
        }
    }

    private Task findTaskOrThrow(UUID taskId) {
        return taskRepository.findById(taskId).orElseThrow(TaskNotFoundException::new);
    }

    private ChecklistItem findItemOrThrow(UUID itemId) {
        return checklistItemRepository.findById(itemId).orElseThrow(ChecklistItemNotFoundException::new);
    }
}
