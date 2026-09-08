package com.pmtracker.project_management_backend.report;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.report.dto.TimeReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/reports")
@Tag(name = "Reports", description = "Отчёты по времени и дашборд проекта")
public class TimeReportController {

    private final TimeReportService timeReportService;

    public TimeReportController(TimeReportService timeReportService) {
        this.timeReportService = timeReportService;
    }

    @GetMapping("/time")
    @Operation(summary = "Отчёт по времени за период",
            description = "Доступно любому участнику, включая VIEWER: записи времени и так видны всем на "
                    + "карточке задачи. Границы включительные; без параметров — последние 30 дней, "
                    + "период длиннее 366 дней — 400 REPORT_RANGE_INVALID. userId сужает отчёт до "
                    + "одного участника. В ответе три разреза одних и тех же часов: по участникам, по "
                    + "задачам и по дням (пустые дни в разрезе по дням присутствуют с нулём). Часы "
                    + "задач, лежащих в корзине, в отчёт не попадают")
    public ResponseEntity<TimeReportResponse> timeReport(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID userId) {
        return ResponseEntity.ok(timeReportService.report(currentUser, projectId, from, to, userId));
    }

    /**
     * Та же выборка, но сырыми строками. Отдаётся текстом с {@code charset=UTF-8} и BOM
     * внутри (см. CsvWriter): заголовок кодировки читают все, кроме Excel, а BOM — Excel.
     */
    @GetMapping("/time.csv")
    @Operation(summary = "CSV-выгрузка записей времени за период",
            description = "Те же фильтры, что у /reports/time, но в ответе не суммы, а сами записи — "
                    + "чтобы сложить их по-своему. RFC 4180, запятая, UTF-8 с BOM; имя файла приезжает "
                    + "в Content-Disposition")
    public ResponseEntity<byte[]> timeReportCsv(
            @AuthenticationPrincipal User currentUser,
            @PathVariable UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID userId) {
        TimeReportCsv csv = timeReportService.exportCsv(currentUser, projectId, from, to, userId);

        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(csv.filename(), StandardCharsets.UTF_8)
                .build();

        // byte[], а не String: тело кодируем сами и ровно один раз, не полагаясь на то,
        // какую кодировку подставит конвертер сообщений под text/csv.
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(csv.content().getBytes(StandardCharsets.UTF_8));
    }
}
