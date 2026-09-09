package com.pmtracker.project_management_backend.realtime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Пустой кадр во все открытые потоки раз в {@code app.realtime.heartbeat} (4.15).
 * <p>
 * Тихое соединение обрывают все и молча: обратные прокси по {@code proxy_read_timeout},
 * мобильные сети — по своим таймерам NAT. Для клиента это выглядит как «поток открыт, но
 * событий почему-то нет», то есть ровно как рабочий поток в спокойный день, — и отличить
 * одно от другого он не может. Регулярный кадр делает тишину заметной: пропала она —
 * пропало и соединение.
 * <p>
 * Второе, ради чего он нужен, — обратное: сервер узнаёт об оборванном соединении только при
 * попытке в него написать. Без heartbeat вкладка, закрытая на ноутбуке, который унесли из
 * сети, держала бы свой {@code SseEmitter} до истечения TTL.
 * <p>
 * <b>Без {@code SchedulerLock}</b>, в отличие от прочих заданий по расписанию (3.8). Тот
 * защищает от того, что два инстанса сделают одну и ту же работу дважды; здесь работа у
 * каждого инстанса своя — его собственные соединения, о которых соседний инстанс не знает
 * и знать не может. Взятая блокировка означала бы, что все инстансы, кроме одного,
 * перестали поддерживать свои потоки живыми.
 */
@Component
public class RealtimeHeartbeatJob {

    private final RealtimeConnectionRegistry registry;

    public RealtimeHeartbeatJob(RealtimeConnectionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Период задан свойством, а не константой, по той же причине, что у сканера дедлайнов:
     * в тестовом профиле его нужно уметь отодвинуть, чтобы фоновый тик не вмешивался в
     * середину проверки. Значение по умолчанию (25 секунд) выбрано с запасом под самый
     * распространённый {@code proxy_read_timeout} nginx — 60 секунд.
     */
    @Scheduled(fixedRateString = "${app.realtime.heartbeat:PT25S}",
            initialDelayString = "${app.realtime.heartbeat:PT25S}")
    public void ping() {
        registry.heartbeat();
    }
}
