package com.pmtracker.project_management_backend.common.exception;

/**
 * Приглашение предъявил пользователь, вошедший под другим адресом.
 * <p>
 * Приглашение адресное: администратор назвал конкретный email и ждёт в проекте именно его
 * владельца. Ссылку при этом ничто не мешает переслать — письмо есть письмо, — и если бы
 * её принимал кто угодно вошедший, пересылка письма молча выдавала бы доступ к приватному
 * проекту человеку, которого никто не звал, а в списке участников появлялся бы кто-то,
 * кого администратор не называл.
 */
public class InvitationEmailMismatchException extends RuntimeException {

    public InvitationEmailMismatchException() {
        super("This invitation was issued for a different email address");
    }
}
