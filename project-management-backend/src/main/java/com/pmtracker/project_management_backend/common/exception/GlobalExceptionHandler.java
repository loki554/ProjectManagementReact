package com.pmtracker.project_management_backend.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

// ResponseEntityExceptionHandler даёт бесплатно правильные HTTP-статусы для всех "фреймворковых"
// исключений MVC (битый JSON, несовпадение типа параметра, неверный HTTP-метод, неизвестный
// media type и т.п. — см. javadoc базового класса) — переопределяем только форматирование тела
// в handleExceptionInternal ниже, чтобы оно всегда соответствовало контракту {error, message},
// на который рассчитан фронтенд (см. errorMessage.js). Раньше эти исключения либо резолвились
// в дефолтную страницу Boot's /error c другой формой JSON, либо (до фикса permitAll("/error")
// в SecurityConfig, см. Phase 8) вообще подменялись на вводящий в заблуждение 401.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidOrExpiredTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidOrExpiredTokenException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_TOKEN", ex.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("INVALID_CREDENTIALS", ex.getMessage()));
    }

    // 400, а не 401: на 401 фронтенд идёт обновлять сессию и, не сумев, разлогинивает —
    // то есть опечатка в текущем пароле выкидывала бы человека из аккаунта (см. client.js).
    @ExceptionHandler(InvalidCurrentPasswordException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCurrentPassword(InvalidCurrentPasswordException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_CURRENT_PASSWORD", ex.getMessage()));
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ErrorResponse> handleEmailNotVerified(EmailNotVerifiedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("EMAIL_NOT_VERIFIED", ex.getMessage()));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("INVALID_REFRESH_TOKEN", ex.getMessage()));
    }

    @ExceptionHandler(InvalidFileException.class)
    public ResponseEntity<ErrorResponse> handleInvalidFile(InvalidFileException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_FILE", ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProjectNotFound(ProjectNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("PROJECT_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ProjectNameAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleProjectNameAlreadyExists(ProjectNameAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("PROJECT_NAME_ALREADY_EXISTS", ex.getMessage()));
    }

    @ExceptionHandler(InvalidProjectNameException.class)
    public ResponseEntity<ErrorResponse> handleInvalidProjectName(InvalidProjectNameException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("PROJECT_NAME_INVALID", ex.getMessage()));
    }

    @ExceptionHandler(NotProjectMemberException.class)
    public ResponseEntity<ErrorResponse> handleNotProjectMember(NotProjectMemberException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("NOT_A_PROJECT_MEMBER", ex.getMessage()));
    }

    @ExceptionHandler(InsufficientProjectRoleException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientProjectRole(InsufficientProjectRoleException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("INSUFFICIENT_ROLE", ex.getMessage()));
    }

    // 403, а не 404: приглашение существует и предъявитель это видит (превью публичное) —
    // отказ здесь именно в том, что оно выписано не на его адрес.
    @ExceptionHandler(InvitationEmailMismatchException.class)
    public ResponseEntity<ErrorResponse> handleInvitationEmailMismatch(InvitationEmailMismatchException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("INVITATION_EMAIL_MISMATCH", ex.getMessage()));
    }

    @ExceptionHandler(AlreadyProjectMemberException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyProjectMember(AlreadyProjectMemberException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ALREADY_PROJECT_MEMBER", ex.getMessage()));
    }

    @ExceptionHandler(CannotRemoveLastOwnerException.class)
    public ResponseEntity<ErrorResponse> handleCannotRemoveLastOwner(CannotRemoveLastOwnerException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CANNOT_REMOVE_LAST_OWNER", ex.getMessage()));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTaskNotFound(TaskNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("TASK_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ParentTaskNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleParentTaskNotFound(ParentTaskNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("PARENT_TASK_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ParentTaskDeletedException.class)
    public ResponseEntity<ErrorResponse> handleParentTaskDeleted(ParentTaskDeletedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("PARENT_TASK_DELETED", ex.getMessage()));
    }

    @ExceptionHandler(ParentTaskProjectMismatchException.class)
    public ResponseEntity<ErrorResponse> handleParentTaskProjectMismatch(ParentTaskProjectMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("PARENT_TASK_PROJECT_MISMATCH", ex.getMessage()));
    }

    @ExceptionHandler(AssigneeNotProjectMemberException.class)
    public ResponseEntity<ErrorResponse> handleAssigneeNotProjectMember(AssigneeNotProjectMemberException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("ASSIGNEE_NOT_PROJECT_MEMBER", ex.getMessage()));
    }

    @ExceptionHandler(InvalidTargetPositionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTargetPosition(InvalidTargetPositionException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_TARGET_POSITION", ex.getMessage()));
    }

    // Массовая правка без единого поля к правке (4.6). Отдельный код, а не общий
    // VALIDATION_ERROR: это единственное состояние формы, из которого пользователю понятно,
    // что делать дальше («выберите, что менять»), и текст для него хочется свой.
    @ExceptionHandler(NoBulkChangesRequestedException.class)
    public ResponseEntity<ErrorResponse> handleNoBulkChanges(NoBulkChangesRequestedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BULK_UPDATE_NO_CHANGES", ex.getMessage()));
    }

    // Сохранённое представление (4.7). Чужое представление сюда приходит тем же исключением,
    // что и несуществующее, — и это намеренно: см. SavedViewService.findOwnOrThrow.
    @ExceptionHandler(SavedViewNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSavedViewNotFound(SavedViewNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("SAVED_VIEW_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(DuplicateSavedViewNameException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateSavedViewName(DuplicateSavedViewNameException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_SAVED_VIEW_NAME", ex.getMessage()));
    }

    @ExceptionHandler(TaskStatusConflictException.class)
    public ResponseEntity<ErrorResponse> handleTaskStatusConflict(TaskStatusConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("TASK_STATUS_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(ConcurrentModificationConflictException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentModification(ConcurrentModificationConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONCURRENT_MODIFICATION", ex.getMessage()));
    }

    /**
     * Тот же конфликт, но пойманный на уровне БД: версия совпала на входе, а к моменту
     * UPDATE строку успели изменить (два запроса пересеклись внутри транзакций). Отвечаем
     * ровно тем же кодом — для пользователя это одно и то же событие, и разводить их в
     * интерфейсе было бы вредно.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException ex) {
        log.debug("Optimistic locking failure", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONCURRENT_MODIFICATION",
                        "The item was changed by someone else while you were editing it"));
    }

    /**
     * Тот же конфликт, но проигранный не на версии, а на блокировке БД: Postgres обнаружил
     * взаимоблокировку и снял одну из транзакций.
     * <p>
     * Ловится это на канбане, где перетаскивание карточки сдвигает позиции соседей: два
     * человека двигают карточки в одной колонке одновременно, транзакции берут те же строки
     * {@code tasks} в разном порядке — и получается классический deadlock. Без этого
     * обработчика он уходил в {@code handleUnexpected} и превращался в 500, хотя ничего не
     * сломалось: проигравшему достаточно перечитать доску и повторить.
     * <p>
     * Отвечаем тем же CONCURRENT_MODIFICATION, что и оптимистичная блокировка выше: для
     * пользователя это одно событие («пока вы тянули, доску изменили»), и разделять их в
     * интерфейсе незачем — действие в обоих случаях одно. {@code PessimisticLockingFailureException},
     * а не только {@code CannotAcquireLockException}: сюда же относится и таймаут ожидания
     * блокировки, у которого и причина, и лечение те же.
     * <p>
     * Обработчик — не замена нормальному порядку захвата строк в reorder'е: он делает отказ
     * честным и повторяемым, но сам deadlock не убирает.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handlePessimisticLockingFailure(PessimisticLockingFailureException ex) {
        log.warn("Database lock could not be acquired while processing request", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONCURRENT_MODIFICATION",
                        "The item was changed by someone else while you were editing it"));
    }

    @ExceptionHandler(TimeLogNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTimeLogNotFound(TimeLogNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("TIME_LOG_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(NotTimeLogOwnerException.class)
    public ResponseEntity<ErrorResponse> handleNotTimeLogOwner(NotTimeLogOwnerException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("NOT_TIME_LOG_OWNER", ex.getMessage()));
    }

    @ExceptionHandler(AttachmentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAttachmentNotFound(AttachmentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("ATTACHMENT_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(NotAttachmentOwnerException.class)
    public ResponseEntity<ErrorResponse> handleNotAttachmentOwner(NotAttachmentOwnerException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("NOT_ATTACHMENT_OWNER", ex.getMessage()));
    }

    @ExceptionHandler(CommentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCommentNotFound(CommentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("COMMENT_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(NotCommentOwnerException.class)
    public ResponseEntity<ErrorResponse> handleNotCommentOwner(NotCommentOwnerException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("NOT_COMMENT_OWNER", ex.getMessage()));
    }

    @ExceptionHandler(UsernameAlreadyTakenException.class)
    public ResponseEntity<ErrorResponse> handleUsernameTaken(UsernameAlreadyTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("USERNAME_TAKEN", ex.getMessage()));
    }

    @ExceptionHandler(NotCommentAuthorException.class)
    public ResponseEntity<ErrorResponse> handleNotCommentAuthor(NotCommentAuthorException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("NOT_COMMENT_AUTHOR", ex.getMessage()));
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotificationNotFound(NotificationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOTIFICATION_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(TagNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTagNotFound(TagNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("TAG_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(DuplicateTagNameException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateTagName(DuplicateTagNameException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_TAG_NAME", ex.getMessage()));
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCategoryNotFound(CategoryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("CATEGORY_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(DuplicateCategoryNameException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCategoryName(DuplicateCategoryNameException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_CATEGORY_NAME", ex.getMessage()));
    }

    @ExceptionHandler(TagProjectMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTagProjectMismatch(TagProjectMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("TAG_PROJECT_MISMATCH", ex.getMessage()));
    }

    // Страховка на случай, если ограничение есть в схеме БД, но не продублировано валидацией DTO
    // (или продублировано, но с другой границей). Без этого обработчика любое нарушение
    // constraint'а — слишком длинная строка, гонка на unique-индексе email/slug — уходило в
    // handleUnexpected ниже и превращалось в 500, то есть выглядело как поломка сервера, хотя
    // виноват запрос. 409, а не 400: до БД доезжают в основном конфликты уникальности, а сами
    // границы длины теперь закрыты @Size на DTO (см. 1.7) и сюда доходить не должны.
    // Логируем целиком (в сообщении драйвера — имя нарушенного constraint'а, по нему и
    // видно, какой валидации не хватает), наружу отдаём общий текст: имена таблиц, колонок
    // и индексов — это детали реализации.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Database constraint violated while processing request", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DATA_CONFLICT", "Request conflicts with existing data"));
    }

    // Единая точка форматирования для ВСЕХ исключений, которые сама MVC резолвит через
    // ResponseEntityExceptionHandler (битый JSON, MethodArgumentTypeMismatchException на
    // /api/tasks/{id} с невалидным UUID, HttpRequestMethodNotSupportedException, а начиная
    // со Spring Framework 7 — и MethodArgumentNotValidException, т.к. базовый класс теперь
    // регистрирует его через свой собственный общий handleException(...) и отдельный
    // @ExceptionHandler(MethodArgumentNotValidException.class) в этом классе конфликтовал бы
    // с ним ("Ambiguous @ExceptionHandler method") — поэтому валидационное сообщение с разбивкой
    // по полям собирается прямо здесь, а не отдельным методом, как раньше).
    // Статус (statusCode) Spring уже определил корректно для каждого случая, здесь только
    // приводим тело к {error, message}. 5xx логируем полностью, но наружу тот же общий текст,
    // что и в handleUnexpected ниже — не течь деталями реализации наружу.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                              HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Unhandled MVC exception while processing request", ex);
            return ResponseEntity.status(statusCode).body(new ErrorResponse("INTERNAL_ERROR", "Something went wrong"));
        }
        if (ex instanceof MethodArgumentNotValidException manve) {
            String message = manve.getBindingResult().getFieldErrors().stream()
                    .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                    .collect(Collectors.joining("; "));
            return ResponseEntity.status(statusCode).body(new ErrorResponse("VALIDATION_ERROR", message));
        }
        // Тот же код/сообщение, что и раньше отдавал отдельный @ExceptionHandler — сохраняем
        // для фронтенда стабильный контракт INVALID_FILE (см. errors.INVALID_FILE), не
        // выставляем наружу сырое сообщение MaxUploadSizeExceededException.
        if (ex instanceof MaxUploadSizeExceededException) {
            return ResponseEntity.status(statusCode).body(new ErrorResponse("INVALID_FILE", "File is too large"));
        }
        // ex.getMessage() тут включает полное имя класса/метода контроллера и DTO
        // (Jackson/Spring пишут это в текст исключения) — не пробрасываем наружу как есть.
        if (ex instanceof HttpMessageNotReadableException) {
            return ResponseEntity.status(statusCode).body(new ErrorResponse("VALIDATION_ERROR", "Malformed request body"));
        }
        return ResponseEntity.status(statusCode).body(new ErrorResponse("VALIDATION_ERROR", ex.getMessage()));
    }

    // Подстраховка от исключений, которые не являются MVC-исключениями (т.е. не проходят через
    // handleExceptionInternal выше) — баг в коде, сбой БД и т.п. Без неё они уходят в Boot's
    // /error и клиент получает не JSON-контракт {error, message}, а либо стандартную страницу
    // ошибки, либо — до фикса permitAll("/error") в SecurityConfig — вводящий в заблуждение 401
    // (см. Phase 8). Сообщение клиенту намеренно общее — не пробрасываем текст/стек внутреннего
    // исключения наружу, это утечка информации о реализации; сам стектрейс уходит в лог сервера.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception while processing request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "Something went wrong"));
    }
}
