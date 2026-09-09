package com.pmtracker.project_management_backend.realtime;

import com.pmtracker.project_management_backend.config.AsyncConfig;
import com.pmtracker.project_management_backend.project.ProjectMemberRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Превращает внутренние события приложения в сообщения открытым потокам (4.15).
 * <p>
 * Устроен ровно как {@code MailDispatcher} и по тем же двум причинам.
 * <p>
 * <b>AFTER_COMMIT.</b> Сигнал «сходи посмотри» не должен опережать то, на что он показывает.
 * Отправленный внутри транзакции, он приводит соседнюю вкладку за задачей, которой в базе
 * ещё нет: она увидит прежнее состояние, решит, что всё уже свежее, и останется с ним до
 * следующего события — то есть живые обновления начали бы систематически показывать
 * позавчерашний день именно в тот момент, ради которого их делали. При откате транзакции
 * это было бы ещё нагляднее: сигнал о правке, которой не было.
 * <p>
 * <b>Отдельный поток.</b> Запись в SSE идёт в сокет клиента, и клиент этот бывает медленный.
 * Делать её на потоке HTTP-запроса значило бы, что скорость чужого канала становится
 * скоростью чужого «сохранить».
 */
@Component
public class RealtimeBroadcaster {

    private final RealtimeConnectionRegistry registry;
    private final ProjectMemberRepository projectMemberRepository;

    public RealtimeBroadcaster(RealtimeConnectionRegistry registry,
                               ProjectMemberRepository projectMemberRepository) {
        this.registry = registry;
        this.projectMemberRepository = projectMemberRepository;
    }

    /**
     * Изменение в проекте — всем его участникам, у кого сейчас открыта вкладка.
     * <p>
     * Получатели считаются пересечением «кто подключён» и «кто состоит в проекте», причём
     * запросом в базу и в этом порядке. Порядок здесь и есть оптимизация: подключённых —
     * единицы и десятки, участников проекта может быть сколько угодно, и спрашивать базу
     * «кто состоит» целиком, чтобы потом выкинуть из ответа почти всех, незачем. Отсюда же
     * ранний выход: без единого открытого потока это событие вообще ничего не стоит.
     * <p>
     * Членство спрашивается заново на каждое событие, а не запоминается при подключении.
     * Так «исключили из проекта» перестаёт действовать сразу же, вместе с обычными правами,
     * а не «когда человек переоткроет вкладку»: второй копии состояния «кто где состоит»,
     * которая может разъехаться с {@code project_members}, здесь просто нет.
     */
    @Async(AsyncConfig.REALTIME_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProjectChanged(ProjectChangedEvent event) {
        Set<UUID> connected = registry.connectedUserIds();
        if (connected.isEmpty()) {
            return;
        }
        List<UUID> recipients =
                projectMemberRepository.findUserIdsByProjectIdAndUserIdIn(event.projectId(), connected);
        registry.send(recipients, RealtimeMessage.project(
                event.projectId(), event.type(), event.taskId(), event.actorId()));
    }

    /**
     * Личное уведомление — одному человеку. Ни членства, ни запроса в базу: получатель
     * известен точно, он же и адресат.
     */
    @Async(AsyncConfig.REALTIME_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserNotified(UserNotifiedEvent event) {
        registry.send(List.of(event.recipientId()), RealtimeMessage.notification(
                event.type(), event.projectId(), event.taskId(), event.actorId()));
    }
}
