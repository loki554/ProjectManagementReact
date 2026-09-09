package com.pmtracker.project_management_backend.realtime;

import com.pmtracker.project_management_backend.common.exception.TooManyRealtimeStreamsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Открытые потоки, сгруппированные по владельцу (4.15).
 * <p>
 * Ключ — пользователь, а не проект, и это не деталь реализации. Вкладка подписывается не на
 * проект, а на себя: колокольчик и список «мои задачи» видны на любой странице, человек
 * ходит между проектами не переоткрывая соединение, а состав его проектов меняется прямо
 * во время сессии (пригласили, исключили). Подписка на проект потребовала бы протокола
 * subscribe/unsubscribe поверх потока и второй копии состояния «кто где состоит» — той
 * самой, которая однажды разъедется с {@code project_members}. Кому какое событие видно,
 * решается в момент рассылки и по базе (см. {@code RealtimeBroadcaster}).
 * <p>
 * <b>Хранилище в памяти инстанса.</b> Пока инстанс один — этого достаточно и это честно.
 * На двух инстансах событие дойдёт только до тех вкладок, что держат соединение с тем же
 * инстансом, где произошла правка, а остальные увидят изменение с обычным обновлением, то
 * есть ровно как до 4.15. Деградация мягкая, но настоящая, и лечится она не здесь, а общей
 * шиной (Redis pub/sub) — ровно тем же способом, каким на нескольких инстансах решаются
 * лимитер (bucket4j-redis) и кэш членства. Заводить её ради одного инстанса значило бы
 * принести в проект брокер и его отказы раньше, чем появится второй инстанс.
 */
@Component
public class RealtimeConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RealtimeConnectionRegistry.class);

    /**
     * Потолок одновременных потоков на одного человека.
     * <p>
     * Соединение стоит памяти и одного слота в пуле асинхронных запросов контейнера, а
     * открывает его вкладка — то есть их ровно столько, сколько вкладок человек забыл
     * закрыть. Шесть — с запасом больше, чем бывает у живого пользователя, и заметно
     * меньше, чем нужно, чтобы одним аккаунтом занять контейнер.
     */
    private static final int MAX_STREAMS_PER_USER = 6;

    private final Map<UUID, Set<SseEmitter>> streamsByUser = new ConcurrentHashMap<>();

    /**
     * Сколько живёт одно соединение, после чего клиент переподключается.
     * <p>
     * Значение по умолчанию (10 минут) выбрано меньше времени жизни access-токена
     * (15 минут, см. {@code app.jwt.access-token-ttl-minutes}) намеренно. Токен на открытом
     * потоке проверяется один раз — при подключении, как у любого HTTP-запроса; чем дольше
     * живёт соединение, тем дольше живёт и это единственное решение о доступе. Поток,
     * который переоткрывается чаще, чем истекает токен, не превращается в дыру в его сроке:
     * вышедший из системы перестаёт получать события не позже, чем перестал бы получать
     * ответы на обычные запросы.
     */
    private final Duration streamTtl;

    public RealtimeConnectionRegistry(@Value("${app.realtime.stream-ttl:PT10M}") Duration streamTtl) {
        this.streamTtl = streamTtl;
    }

    /**
     * Заводит поток для пользователя.
     *
     * @throws TooManyRealtimeStreamsException если у человека уже открыто
     *                                         {@value #MAX_STREAMS_PER_USER} потоков
     */
    public SseEmitter open(UUID userId) {
        SseEmitter emitter = new SseEmitter(streamTtl.toMillis());
        // Все три исхода ведут в одно и то же — забыть соединение. Разница между ними
        // (клиент закрыл вкладку / истёк TTL / оборвалась сеть) интересна только логам:
        // писать в мёртвый emitter одинаково бессмысленно во всех трёх случаях.
        emitter.onCompletion(() -> forget(userId, emitter));
        emitter.onTimeout(() -> {
            // Без явного complete() Spring оставит асинхронный запрос висеть до таймаута
            // контейнера — то есть TTL перестал бы что-либо ограничивать.
            emitter.complete();
            forget(userId, emitter);
        });
        emitter.onError(error -> forget(userId, emitter));

        // computeIfAbsent + проверка внутри compute: два одновременных открытия одного
        // пользователя иначе оба увидели бы «пять из шести» и оба добавились бы шестым и
        // седьмым. Здесь же лимит проверяется под замком сегмента карты.
        boolean[] rejected = {false};
        streamsByUser.compute(userId, (id, existing) -> {
            Set<SseEmitter> streams = existing != null ? existing : new CopyOnWriteArraySet<>();
            if (streams.size() >= MAX_STREAMS_PER_USER) {
                rejected[0] = true;
                return streams;
            }
            streams.add(emitter);
            return streams;
        });
        if (rejected[0]) {
            throw new TooManyRealtimeStreamsException();
        }
        return emitter;
    }

    /** Кому сейчас вообще есть смысл что-то слать. */
    public Set<UUID> connectedUserIds() {
        return Set.copyOf(streamsByUser.keySet());
    }

    public void send(Collection<UUID> userIds, RealtimeMessage message) {
        for (UUID userId : userIds) {
            for (SseEmitter emitter : streamsByUser.getOrDefault(userId, Set.of())) {
                write(userId, emitter, SseEmitter.event().name("change").data(message));
            }
        }
    }

    /**
     * Пустой комментарий во все живые потоки. Нужен обеим сторонам: прокси и мобильные сети
     * закрывают соединение, по которому долго ничего не шло, а сервер иначе не узнаёт об
     * оборванном соединении до первого настоящего события — то есть, возможно, никогда.
     *
     * @return сколько потоков осталось живо (для логов и тестов)
     */
    public int heartbeat() {
        int alive = 0;
        for (Map.Entry<UUID, Set<SseEmitter>> entry : streamsByUser.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                if (write(entry.getKey(), emitter, SseEmitter.event().comment("ping"))) {
                    alive++;
                }
            }
        }
        return alive;
    }

    /** @return true, если запись удалась */
    private boolean write(UUID userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            // Обычное дело, а не сбой: вкладку закрыли, сеть отвалилась, поток уже завершён
            // таймаутом. Отсюда debug, а не warn, — и completeWithError, чтобы контейнер
            // отпустил асинхронный запрос, а не ждал TTL.
            log.debug("Dropping a realtime stream of user {}: {}", userId, e.toString());
            forget(userId, emitter);
            try {
                emitter.completeWithError(e);
            } catch (RuntimeException alreadyFinished) {
                // Гонка с onTimeout/onCompletion: соединение уже закрыто кем-то ещё.
                // Ровно то, чего мы и добивались, — молчим.
            }
            return false;
        }
    }

    private void forget(UUID userId, SseEmitter emitter) {
        // Пустой набор удаляется вместе с ключом: connectedUserIds читается на каждое
        // событие, и оставленные пустые множества превратили бы её в список всех, кто
        // когда-либо подключался за время жизни процесса.
        streamsByUser.computeIfPresent(userId, (id, streams) -> {
            streams.remove(emitter);
            return streams.isEmpty() ? null : streams;
        });
    }
}
