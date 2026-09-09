package com.pmtracker.project_management_backend.realtime;

import com.pmtracker.project_management_backend.auth.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Живые обновления (4.15): один поток на вкладку, по которому приезжают сигналы «в проекте
 * P изменилось то-то» и «вам пришло уведомление».
 *
 * <h2>Почему SSE, а не WebSocket</h2>
 * Весь обмен здесь односторонний: браузер уже умеет всё, что нужно, обычными REST-запросами,
 * и обратный канал ему не нужен ни для чего. WebSocket дал бы этот канал вместе со своим
 * протоколом рукопожатия, своей моделью авторизации (существующий фильтр JWT на него не
 * распространяется), своим форматом кадров и, как правило, STOMP с брокером сверху. SSE —
 * это обычный HTTP-ответ, который просто долго не заканчивается: те же заголовки, тот же
 * фильтр аутентификации, тот же CORS, те же логи и прокси. Меняется одна вещь — ответ
 * приходит по частям.
 *
 * <h2>Почему Bearer, а не токен в адресе</h2>
 * Штатный браузерный {@code EventSource} не умеет отправлять заголовки — отсюда
 * распространённое {@code /stream?token=...}. Токен в адресной строке попадает в журналы
 * прокси и сервера, в историю браузера и в {@code Referer}; для доступа ко всему API это
 * слишком дорогая плата за одну строчку клиентского кода. Поэтому поток на клиенте читается
 * через {@code fetch} с обычным {@code Authorization: Bearer} (см. {@code lib/eventStream.js}),
 * а разбор кадров SSE — тридцать строк, которые ничего не знают о предметной области.
 * Приятный побочный эффект: переподключением и его паузами управляем мы, а не браузер.
 *
 * <h2>Прав здесь никаких</h2>
 * Ручка требует только аутентификации: она не отдаёт содержимого — только сигналы о том,
 * что стоит перечитать. Что человеку видно, решает рассылка (членство в проекте,
 * см. {@code RealtimeBroadcaster}), а что ему отдадут — обычные контроллеры, когда он за
 * этим придёт. Сигнал, доставленный по ошибке, приводит к запросу, на который сервер
 * отвечает 403, и ни к чему больше (см. {@link RealtimeMessage}).
 */
@RestController
@RequestMapping("/api/realtime")
@Tag(name = "Realtime", description = "Поток изменений через SSE (4.15)")
public class RealtimeController {

    private final RealtimeConnectionRegistry registry;

    public RealtimeController(RealtimeConnectionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Открыть поток изменений",
            description = "Ответ не заканчивается: в него приходят события change с полями "
                    + "{scope, type, projectId, taskId, actorId} и комментарии-heartbeat. "
                    + "Данных сущностей в потоке нет — только сигнал перечитать. "
                    + "429, если у пользователя уже открыто слишком много потоков")
    public SseEmitter stream(@AuthenticationPrincipal User currentUser) {
        SseEmitter emitter = registry.open(currentUser.getId());
        // Первое событие уходит сразу и намеренно. Оно доказывает клиенту, что поток
        // действительно открыт (а не висит в ожидании ответа прокси), и тем самым разрешает
        // ему сбросить паузу переподключения — иначе отличить «подключились» от «сервер
        // принял запрос и замолчал» можно было бы только по первому изменению в проекте,
        // то есть, возможно, не сегодня.
        try {
            emitter.send(SseEmitter.event().name("ready").data(""));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
