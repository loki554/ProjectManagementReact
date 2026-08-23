package com.pmtracker.project_management_backend.common.exception;

/**
 * Текущий пароль в запросе на смену пароля не совпал с сохранённым.
 * <p>
 * Намеренно НЕ переиспользует InvalidCredentialsException: тот отдаёт 401, а 401 на любом
 * запросе фронтенд трактует как протухшую сессию (интерсептор в client.js идёт обновлять
 * токен и, не сумев, разлогинивает). Опечатка в текущем пароле выкидывала бы человека
 * из аккаунта, поэтому здесь отдельный код и 400.
 */
public class InvalidCurrentPasswordException extends RuntimeException {

    public InvalidCurrentPasswordException() {
        super("Current password is incorrect");
    }
}
