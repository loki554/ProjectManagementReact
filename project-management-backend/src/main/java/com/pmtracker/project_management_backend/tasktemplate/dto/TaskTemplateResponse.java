package com.pmtracker.project_management_backend.tasktemplate.dto;

import com.pmtracker.project_management_backend.category.dto.CategorySummary;
import com.pmtracker.project_management_backend.tag.dto.TagSummary;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import com.pmtracker.project_management_backend.tasktemplate.TaskTemplate;
import com.pmtracker.project_management_backend.tasktemplate.TaskTemplateItem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Шаблон задачи целиком — и для страницы управления шаблонами, и для формы заведения
 * задачи, которая по нему заполняется.
 *
 * <p>Один ответ на два экрана, а не «краткий» и «полный»: шаблон — это десяток строк, и
 * форма, которой он нужен, всё равно берёт из него всё, кроме дат. Вторая, урезанная форма
 * ответа появилась бы ровно для того, чтобы однажды разъехаться с первой.
 *
 * @param items     пункты чек-листа по порядку; в списке шаблонов приезжает пустым, см.
 *                  {@code itemCount}
 * @param itemCount сколько в шаблоне пунктов — подпись в списке выбора, которая не требует
 *                  тянуть сами пункты
 */
public record TaskTemplateResponse(
        UUID id,
        UUID projectId,
        String name,
        String title,
        String description,
        TaskUrgency urgency,
        TagSummary tag,
        CategorySummary category,
        List<TaskTemplateItemResponse> items,
        long itemCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static TaskTemplateResponse from(TaskTemplate template, List<TaskTemplateItem> items) {
        return build(template, items.stream().map(TaskTemplateItemResponse::from).toList(), items.size());
    }

    /** Строка списка: без пунктов, только с их числом. */
    public static TaskTemplateResponse summary(TaskTemplate template, long itemCount) {
        return build(template, List.of(), itemCount);
    }

    private static TaskTemplateResponse build(TaskTemplate template,
                                              List<TaskTemplateItemResponse> items,
                                              long itemCount) {
        return new TaskTemplateResponse(
                template.getId(),
                template.getProject().getId(),
                template.getName(),
                template.getTitle(),
                template.getDescription(),
                template.getUrgency(),
                template.getTag() != null ? TagSummary.from(template.getTag()) : null,
                template.getCategory() != null ? CategorySummary.from(template.getCategory()) : null,
                items,
                itemCount,
                template.getCreatedAt(),
                template.getUpdatedAt()
        );
    }
}
