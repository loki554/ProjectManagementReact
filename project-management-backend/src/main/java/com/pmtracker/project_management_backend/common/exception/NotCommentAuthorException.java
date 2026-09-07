package com.pmtracker.project_management_backend.common.exception;

/**
 * Правку чужого комментария (4.4) отделяем от удаления чужого ({@link NotCommentOwnerException})
 * своим кодом ошибки, потому что это разные права, а не разная строгость одного.
 * <p>
 * Удалять чужой комментарий OWNER/ADMIN может — это модерация, и её видно: сообщение
 * исчезает целиком. Править чужой текст не может никто: подпись под комментарием остаётся
 * прежней, а слова становятся другими. С общим кодом ADMIN получал бы на PATCH ответ
 * «удалить может только автор или администратор проекта» — то есть сообщение, прямо
 * противоречащее тому, что с ним только что произошло.
 */
public class NotCommentAuthorException extends RuntimeException {

    public NotCommentAuthorException() {
        super("Only the author of the comment can edit it");
    }
}
