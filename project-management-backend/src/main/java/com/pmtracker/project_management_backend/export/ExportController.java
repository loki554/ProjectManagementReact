package com.pmtracker.project_management_backend.export;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.export.dto.ProjectTasksExport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Выгрузка данных проекта (4.12).
 *
 * <p>Расширение стоит в адресе, а не в заголовке {@code Accept}: по этим ссылкам ходят не
 * только из приложения, но и curl'ом из скрипта, а «дай мне tasks.csv» короче и надёжнее,
 * чем «дай мне tasks с правильным Accept» — тот же приём, что у {@code /reports/time.csv}.
 *
 * <p>Все три ответа помечены {@code Content-Disposition: attachment}, включая JSON: файл
 * просят, чтобы сохранить, а не чтобы посмотреть его в соседней вкладке. Заголовок доезжает
 * до браузера благодаря {@code exposedHeaders} в SecurityConfig — без него имя, собранное
 * сервером, до JS не добирается (см. ExportService.filename).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/export")
@Tag(name = "Export", description = "Выгрузка задач и вики проекта: забрать свои данные файлом")
public class ExportController {

    /** Тип для .md: RFC 7763, понимают редакторы и «Сохранить как» в браузере. */
    private static final MediaType TEXT_MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/tasks.csv")
    @Operation(summary = "Задачи проекта в CSV",
            description = "Все задачи проекта, включая подзадачи и задачи вне спринтов; задачи из корзины "
                    + "не выгружаются. Без фильтров и пагинации: файл — это снимок проекта целиком, "
                    + "сузить его можно уже в таблице. Статус и срочность — кодами (IN_PROGRESS), даты — "
                    + "ISO-8601 в UTC. RFC 4180, запятая, UTF-8 с BOM. Доступно любому участнику, "
                    + "включая VIEWER")
    public ResponseEntity<byte[]> tasksCsv(@AuthenticationPrincipal User currentUser,
                                           @PathVariable UUID projectId) {
        ExportFile<String> file = exportService.tasksCsv(currentUser, projectId);
        // byte[], а не String: тело кодируем сами и ровно один раз, не полагаясь на то,
        // какую кодировку подставит конвертер сообщений под text/csv.
        return attachment(file.filename(), new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(file.content().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/tasks.json")
    @Operation(summary = "Задачи проекта в JSON",
            description = "Тот же набор задач, что и в CSV, но со структурой и типами: исполнитель — "
                    + "объект, отсутствующее поле — null, а не пустая строка. В шапке — проект и момент "
                    + "снятия выгрузки. Доступно любому участнику, включая VIEWER")
    public ResponseEntity<ProjectTasksExport> tasksJson(@AuthenticationPrincipal User currentUser,
                                                        @PathVariable UUID projectId) {
        ExportFile<ProjectTasksExport> file = exportService.tasksJson(currentUser, projectId);
        // Тело сериализует Jackson — тот же ObjectMapper, что и у остальных ответов API,
        // чтобы формат дат в выгрузке совпадал с форматом дат во всём приложении.
        return attachment(file.filename(), MediaType.APPLICATION_JSON).body(file.content());
    }

    @GetMapping("/wiki.md")
    @Operation(summary = "Вики проекта в Markdown",
            description = "Ровно тот текст, который лежит в вики, без добавленного заголовка и без BOM. "
                    + "У проекта, где вики ещё не заводили, — пустой файл, а не 404. Доступно любому "
                    + "участнику, включая VIEWER")
    public ResponseEntity<byte[]> wikiMarkdown(@AuthenticationPrincipal User currentUser,
                                               @PathVariable UUID projectId) {
        ExportFile<String> file = exportService.wikiMarkdown(currentUser, projectId);
        return attachment(file.filename(), TEXT_MARKDOWN)
                .body(file.content().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Общая шапка ответа-файла. Имя кодируется по RFC 5987 ({@code filename*=UTF-8''...}) —
     * слаг проекта латиницей, но подставлять его в заголовок без кодирования значило бы
     * полагаться на то, что он таким и останется.
     */
    private static ResponseEntity.BodyBuilder attachment(String filename, MediaType contentType) {
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString());
    }
}
