package com.pmtracker.project_management_backend.export;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.common.csv.CsvWriter;
import com.pmtracker.project_management_backend.export.dto.ExportProject;
import com.pmtracker.project_management_backend.export.dto.ExportUser;
import com.pmtracker.project_management_backend.export.dto.ProjectTasksExport;
import com.pmtracker.project_management_backend.export.dto.TaskExportRow;
import com.pmtracker.project_management_backend.project.Project;
import com.pmtracker.project_management_backend.project.ProjectAccessService;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskRepository;
import com.pmtracker.project_management_backend.timelog.TimeLogRepository;
import com.pmtracker.project_management_backend.wiki.ProjectWiki;
import com.pmtracker.project_management_backend.wiki.ProjectWikiRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Выгрузка данных проекта (4.12): задачи в CSV и JSON, вики в Markdown.
 *
 * <p><b>Зачем пункт.</b> Всё, что трекер знал о проекте, до сих пор можно было увидеть, но
 * нельзя было забрать: ни свести задачи в сводной таблице, ни положить копию в архив, ни
 * уйти в другой инструмент. Это тот самый вопрос «а как забрать свои данные», на который
 * продукт обязан отвечать не рассказом про доступ к базе.
 *
 * <p><b>Выгружается проект целиком, без фильтров.</b> Список задач умеет восемь фильтров, и
 * протянуть их сюда было бы несложно, — но файл и экран отвечают на разные вопросы. Экран
 * показывает то, что нужно сейчас; файл — это снимок, и снимок с чьими-то вчерашними
 * фильтрами внутри бесполезен ровно тогда, когда за ним обратятся. А сузить набор строк
 * тот, кто открыл CSV, сможет в той же таблице, ради которой он его и просил.
 *
 * <p><b>Два формата, потому что читатели разные.</b> CSV открывают глазами: он плоский, в
 * нём тэг и категория — это их названия, а перенос строки внутри описания экранирован по
 * RFC 4180. JSON разбирают скриптом: в нём остаются типы, null отличается от пустой строки,
 * а исполнитель — это объект с id и email, а не склеенная строка, которую пришлось бы
 * разбирать обратно. Одного формата на обоих читателей не бывает.
 *
 * <p><b>Права те же, что у отчёта и дашборда (4.10, 4.11)</b> — любой участник, включая
 * VIEWER. Выгрузка не показывает ничего, чего не видно на доске, в списке задач и на
 * странице вики; закрывать файл от того, кому доступно всё его содержимое построчно,
 * значило бы завести право, которое обходится копированием экрана.
 */
@Service
public class ExportService {

    /**
     * Колонки CSV. Описание — последним, хотя в карточке задачи оно второе: оно единственное
     * многострочное, и таблица, в середине которой стоит абзац текста, нечитаема в любом
     * редакторе.
     */
    private static final List<String> TASKS_CSV_HEADER = List.of(
            "Номер", "Родитель", "Название", "Статус", "Срочность", "Исполнитель",
            "Email исполнителя", "Автор", "Категория", "Тэг", "Спринт", "Срок",
            "Часы", "Создана", "Обновлена", "Описание");

    private final TaskRepository taskRepository;
    private final TimeLogRepository timeLogRepository;
    private final ProjectWikiRepository projectWikiRepository;
    private final ProjectAccessService projectAccessService;

    public ExportService(TaskRepository taskRepository,
                         TimeLogRepository timeLogRepository,
                         ProjectWikiRepository projectWikiRepository,
                         ProjectAccessService projectAccessService) {
        this.taskRepository = taskRepository;
        this.timeLogRepository = timeLogRepository;
        this.projectWikiRepository = projectWikiRepository;
        this.projectAccessService = projectAccessService;
    }

    /**
     * Задачи таблицей. Строки идут по номерам задач подряд, включая подзадачи: дерево несёт
     * колонка «Родитель», а не порядок строк. Разложить подзадачи под родителями значило бы,
     * что «найти задачу №57» в файле превращается в обход дерева, а сортировка по любой
     * другой колонке этот порядок всё равно сломает.
     */
    @Transactional(readOnly = true)
    public ExportFile<String> tasksCsv(User currentUser, UUID projectId) {
        Project project = requireAccess(projectId, currentUser);
        List<TaskExportRow> rows = loadTasks(projectId);

        List<List<String>> cells = rows.stream().map(ExportService::toCsvRow).toList();

        return new ExportFile<>(filename(project, "tasks", "csv"), CsvWriter.write(TASKS_CSV_HEADER, cells));
    }

    /** Те же задачи, но со структурой и типами — для того, кто разбирает файл скриптом. */
    @Transactional(readOnly = true)
    public ExportFile<ProjectTasksExport> tasksJson(User currentUser, UUID projectId) {
        Project project = requireAccess(projectId, currentUser);
        ProjectTasksExport payload = ProjectTasksExport.of(ExportProject.from(project), loadTasks(projectId));
        return new ExportFile<>(filename(project, "tasks", "json"), payload);
    }

    /**
     * Вики как есть — ровно тот Markdown, который человек видел в редакторе, без
     * дописанного заголовка и без служебной шапки. Страница уже в нужном формате, и файл
     * обязан открыться тем же текстом: любой добавленный заголовок при следующем
     * сохранении вернулся бы в вики содержимым и удвоился.
     *
     * <p>BOM, в отличие от CSV, не ставится: он нужен Excel'ю, а Markdown читают редакторы
     * и генераторы сайтов, для которых он — лишний символ в начале первой строки, из-за
     * которого заголовок «# Название» перестаёт быть заголовком.
     *
     * <p>У проекта без вики выгрузка — пустой файл, а не 404: «страницы ещё не написали» —
     * это состояние проекта, а не ошибка запроса, и отвечать на него ошибкой значило бы,
     * что кнопка «Скачать» иногда ломается без причины.
     */
    @Transactional(readOnly = true)
    public ExportFile<String> wikiMarkdown(User currentUser, UUID projectId) {
        Project project = requireAccess(projectId, currentUser);
        String content = projectWikiRepository.findByProjectId(projectId)
                .map(ProjectWiki::getContent)
                .orElse("");
        return new ExportFile<>(filename(project, "wiki", "md"), content);
    }

    // ------------------------------------------------------------------------- внутреннее

    /**
     * Строка задачи в порядке колонок {@link #TASKS_CSV_HEADER}.
     *
     * <p>Статус и срочность выгружаются кодами ({@code IN_PROGRESS}), а не подписями с
     * экрана: подписи живут в i18n фронтенда и переведены на три языка, и вторая их копия
     * на сервере разошлась бы с первой на первом же переименовании. Код читается и
     * человеком, и скриптом, и не зависит от того, на каком языке сидел тот, кто снял
     * выгрузку.
     */
    private static List<String> toCsvRow(TaskExportRow row) {
        ExportUser assignee = row.assignee();
        return Arrays.asList(
                String.valueOf(row.taskNumber()),
                row.parentTaskNumber() != null ? "#" + row.parentTaskNumber() : "",
                row.title(),
                row.status().name(),
                row.urgency().name(),
                assignee != null ? fullName(assignee.lastName(), assignee.firstName()) : "",
                assignee != null ? assignee.email() : "",
                fullName(row.createdBy().lastName(), row.createdBy().firstName()),
                nullToEmpty(row.category()),
                nullToEmpty(row.tag()),
                nullToEmpty(row.sprint()),
                formatInstant(row.dueDate()),
                // toPlainString, а не toString: у BigDecimal из NUMERIC(5,2) экспоненты не
                // бывает, но полагаться на это в выгрузке незачем (та же оговорка, что в
                // TimeReportService).
                row.hoursSpent().toPlainString(),
                formatInstant(row.createdAt()),
                formatInstant(row.updatedAt()),
                nullToEmpty(row.description()));
    }

    private Project requireAccess(UUID projectId, User currentUser) {
        Project project = projectAccessService.findProjectOrThrow(projectId);
        projectAccessService.requireMembership(projectId, currentUser);
        return project;
    }

    /**
     * Строки выгрузки. Часы догружаются одним батчем на весь проект (та же причина, что в
     * TaskService.toResponses): сумма по задаче — это отдельный запрос, и звать его на
     * каждую строку означало бы N+1 ровно там, где строк больше всего.
     */
    private List<TaskExportRow> loadTasks(UUID projectId) {
        List<Task> tasks = taskRepository.findAllForExport(projectId);
        if (tasks.isEmpty()) {
            return List.of();
        }

        Map<UUID, BigDecimal> hoursByTask = timeLogRepository
                .sumHoursByTaskIds(tasks.stream().map(Task::getId).toList()).stream()
                .collect(Collectors.toMap(TimeLogRepository.TaskHoursTotal::getTaskId,
                        TimeLogRepository.TaskHoursTotal::getTotalHours));

        return tasks.stream()
                .map(task -> TaskExportRow.from(task, hoursByTask.getOrDefault(task.getId(), BigDecimal.ZERO)))
                .toList();
    }

    /**
     * Имя файла: что выгружено, из какого проекта и когда снято. Дата — вместо периода в
     * имени CSV-отчёта (4.10): у выгрузки периода нет, а две копии одного проекта, снятые
     * с разницей в неделю, различать надо ровно так же.
     */
    private static String filename(Project project, String what, String extension) {
        return "%s-%s-%s.%s".formatted(what, project.getSlug(), LocalDate.now(), extension);
    }

    /**
     * Момент в ISO-8601 с секундами и в UTC. Без пояса одна и та же строка означала бы
     * разное время на разных машинах, а «Z» на конце читают и pandas, и Google Sheets, и
     * человек. Миллисекунды отброшены: в сроке задачи они не значат ничего, а колонку
     * удлиняют заметно.
     */
    private static String formatInstant(Instant instant) {
        return instant != null ? instant.truncatedTo(ChronoUnit.SECONDS).toString() : "";
    }

    private static String fullName(String lastName, String firstName) {
        return (nullToEmpty(lastName) + " " + nullToEmpty(firstName)).trim();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
