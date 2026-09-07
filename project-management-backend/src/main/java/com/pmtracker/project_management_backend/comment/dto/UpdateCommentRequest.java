package com.pmtracker.project_management_backend.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Правка комментария (4.4). Поле ровно одно и совпадает с {@link CreateCommentRequest} —
 * отдельный тип заведён не ради полей, а ради того, чтобы у двух разных операций были
 * разные контракты: у создания тело обязательно всегда, а у правки к нему в любой момент
 * может добавиться что-то своё (например, признак «не уведомлять»), и делить это с
 * созданием пришлось бы полем, которое там ничего не значит.
 * <p>
 * Ограничения те же 2000 символов: правка не должна открывать способ записать в комментарий
 * то, чего нельзя было написать сразу.
 */
public record UpdateCommentRequest(
        @NotBlank @Size(max = 2000) String body
) {
}
