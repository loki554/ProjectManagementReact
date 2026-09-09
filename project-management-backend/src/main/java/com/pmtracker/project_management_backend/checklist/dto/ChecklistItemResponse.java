package com.pmtracker.project_management_backend.checklist.dto;

import com.pmtracker.project_management_backend.checklist.ChecklistItem;

import java.util.UUID;

/**
 * Пункт чек-листа задачи (4.13).
 *
 * <p>Ни автора, ни времени: пункт живёт минуты между «написали» и «отметили», и подпись под
 * ним заняла бы больше места, чем сам текст. Кто и когда трогал задачу, отвечает лента
 * активности проекта.
 */
public record ChecklistItemResponse(
        UUID id,
        UUID taskId,
        String content,
        boolean done,
        int position
) {
    public static ChecklistItemResponse from(ChecklistItem item) {
        return new ChecklistItemResponse(
                item.getId(),
                item.getTask().getId(),
                item.getContent(),
                item.isDone(),
                item.getPosition()
        );
    }
}
