package com.pmtracker.project_management_backend.project;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.exception.InsufficientProjectRoleException;
import com.pmtracker.project_management_backend.common.exception.NotProjectMemberException;
import com.pmtracker.project_management_backend.common.exception.ProjectArchivedException;
import com.pmtracker.project_management_backend.common.exception.ProjectNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Общая точка проверки "проект существует / пользователь его участник / роль участника
 * достаточна" — используется и ProjectService (CRUD проекта), и ProjectMemberService
 * (управление участниками), чтобы не дублировать логику ролей в двух местах.
 */
@Service
public class ProjectAccessService {

    private final ProjectRepository projectRepository;
    private final ProjectMembershipCache membershipCache;

    public ProjectAccessService(ProjectRepository projectRepository, ProjectMembershipCache membershipCache) {
        this.projectRepository = projectRepository;
        this.membershipCache = membershipCache;
    }

    public Project findProjectOrThrow(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(ProjectNotFoundException::new);
    }

    /**
     * Роль пользователя в проекте, либо 403, если он не участник.
     *
     * <p>Возвращается роль, а не строка участника: вызывающему коду от неё больше ничего и
     * не нужно, а отдавать наружу сущность из проверки доступа — значит однажды получить
     * место, которое эту сущность меняет, и кэш (3.10), раздающий её всем сразу.
     */
    public ProjectRole requireMembership(UUID projectId, User user) {
        return membershipCache.findRole(projectId, user.getId())
                .orElseThrow(NotProjectMemberException::new);
    }

    /**
     * Единственная дверь ко всякой правке внутри проекта: роль не ниже требуемой И проект
     * не в архиве (4.14).
     *
     * <p>Две проверки склеены в один метод намеренно. Архив — это не бейдж на карточке, а
     * обещание, что законченный проект больше не меняется; выполнить такое обещание можно
     * только там, где нельзя забыть его проверить. Мест правки в трекере три десятка
     * (задачи, комментарии, время, вложения, вики, тэги, категории, спринты, участники), и
     * отдельная проверка «а не архив ли» рядом с каждым из них однажды была бы пропущена —
     * причём пропуск не сломал бы ничего заметного, а просто позволил бы дописать
     * комментарий в проект, закрытый полгода назад.
     *
     * <p>Ровно поэтому у метода нет варианта «только роль»: старый {@code requireRole(role,
     * required)} удалён, а не оставлен рядом. Пока он существует, добавить новый write-путь
     * мимо архива — вопрос одного автодополнения; без него код, забывший про архив, просто
     * не компилируется. Исключение одно и названо так, чтобы его было видно в диффе, —
     * {@link #requireOwnerIgnoringArchive}.
     *
     * <p>Порядок проверок важен: сначала роль, потом архив. Постороннему и наблюдателю
     * незачем узнавать из кода ошибки, что проект в архиве, — для них он закрыт в любом
     * состоянии.
     */
    public void requireWriteRole(Project project, ProjectRole role, ProjectRole required) {
        if (!role.isAtLeast(required)) {
            throw new InsufficientProjectRoleException();
        }
        if (project.isArchived()) {
            throw new ProjectArchivedException();
        }
    }

    /**
     * То же самое там, где на руках только id проекта. Лишнего запроса в БД это, как
     * правило, не стоит: вызывающий код почти всегда уже загрузил проект или задачу с ним в
     * той же транзакции, и findById отдаёт его из persistence context.
     */
    public void requireWriteRole(UUID projectId, ProjectRole role, ProjectRole required) {
        requireWriteRole(findProjectOrThrow(projectId), role, required);
    }

    /**
     * Роль для чтения, закрытого не всем участникам. Архива не касается: архив запрещает
     * менять проект, а не смотреть в него, — иначе «остаётся доступным» превратилось бы в
     * «остаётся, но не покажем».
     *
     * <p>Такое чтение в трекере ровно одно — список неотвеченных приглашений
     * (ProjectInvitationService.list): это адреса людей, которые в проект ещё не вошли, и
     * видеть их должен тот, кто управляет составом, а не любой участник. Всё остальное
     * читается по членству.
     */
    public void requireReadRole(ProjectRole role, ProjectRole required) {
        if (!role.isAtLeast(required)) {
            throw new InsufficientProjectRoleException();
        }
    }

    /**
     * Проверка роли владельца, сознательно не смотрящая на архив. Нужна ровно двум
     * действиям, и оба они и есть выход из архива: удалить архивный проект насовсем и
     * вернуть его из архива. Если бы они шли через {@link #requireWriteRole}, архив стал бы
     * состоянием без выхода — заархивированный проект нельзя было бы ни разархивировать, ни
     * удалить.
     */
    public void requireOwnerIgnoringArchive(ProjectRole role) {
        if (!role.isAtLeast(ProjectRole.OWNER)) {
            throw new InsufficientProjectRoleException();
        }
    }

    /**
     * Владение — граница, за которую ADMIN не пускают: выдавать и отбирать роль OWNER может
     * только OWNER. Обычной проверки «не ниже ADMIN» тут мало, и «должен остаться хотя бы один
     * владелец» тоже не спасает: администратор повышает себя до OWNER (владельцев становится
     * двое, guard молчит), понижает настоящего владельца — и проект у него. Ровно те же три
     * двери ведут внутрь: смена роли, исключение участника и приглашение сразу с ролью OWNER,
     * поэтому проверка одна на всех, а не по месту.
     */
    public void requireOwnerForOwnershipChange(ProjectRole role) {
        if (role != ProjectRole.OWNER) {
            throw new InsufficientProjectRoleException();
        }
    }
}
