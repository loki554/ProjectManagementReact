package com.pmtracker.project_management_backend.storage;

import com.pmtracker.project_management_backend.attachment.AttachmentRepository;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Суточная сверка хранилища с базой: удаление файлов, на которые больше никто не ссылается
 * (3.6).
 * <p>
 * Откуда они берутся. Удаление вложения через API чистит и строку, и файл
 * ({@code AttachmentService.delete}), а вот каскадное удаление — нет: при удалении проекта
 * строки attachments уходят по ON DELETE CASCADE, и до файлов каскад не достаёт. То же
 * самое делает чистка корзины (3.5) и любая замена аватарки или превью, оборвавшаяся между
 * записью файла и коммитом. Каждый такой случай оставляет на диске файл, который уже никогда
 * никем не будет прочитан и никем не будет удалён.
 * <p>
 * Почему сверка, а не явное удаление файлов в каждом из этих мест. Во-первых, их много и
 * список растёт: любое новое место, забывшее про файлы, добавляет утечку, которую никто не
 * заметит. Во-вторых, удалять файл внутри транзакции нельзя — откат вернёт строку, но не
 * файл, и вложение превратится в битую ссылку; а после коммита это уже отдельный шаг,
 * который может не выполниться. Сверка закрывает и то, и другое разом, потому что смотрит
 * на результат, а не на намерение.
 * <p>
 * Как и остальные уборщики, джоб идемпотентен и не нуждается в распределённой блокировке:
 * второй инстанс увидит то же состояние и удалит то же самое (а точнее, уже ничего).
 */
@Component
public class OrphanedFileCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OrphanedFileCleanupJob.class);

    private final FileStorageService fileStorageService;
    private final AttachmentRepository attachmentRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final Duration minimumAge;

    public OrphanedFileCleanupJob(FileStorageService fileStorageService,
                                  AttachmentRepository attachmentRepository,
                                  UserRepository userRepository,
                                  ProjectRepository projectRepository,
                                  // Возраст, младше которого файл не трогаем. Смысл строго
                                  // технический: файл пишется ДО коммита строки, которая на
                                  // него ссылается, поэтому у только что загруженного вложения
                                  // есть окно, когда оно на диске уже есть, а в базе ещё нет.
                                  // Сутки — с гигантским запасом больше этого окна; свойство
                                  // существует, чтобы тест не ждал сутки.
                                  @Value("${app.storage.orphan-cleanup.minimum-age:P1D}") Duration minimumAge) {
        this.fileStorageService = fileStorageService;
        this.attachmentRepository = attachmentRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.minimumAge = minimumAge;
    }

    // Своё окно, отличное от чистки токенов (3:30) и корзины (3:45): три уборки, начатые
    // одновременно, соревновались бы за одно и то же соединение с БД без всякой нужды.
    @Scheduled(cron = "${app.storage.orphan-cleanup.cron:0 0 4 * * *}")
    @Transactional(readOnly = true)
    public void deleteOrphanedFiles() {
        Set<String> referenced = collectReferencedPaths();
        Instant cutoff = Instant.now().minus(minimumAge);

        int deleted = 0;
        for (StoredObject object : fileStorageService.listAll()) {
            if (referenced.contains(object.relativePath()) || object.lastModifiedAt().isAfter(cutoff)) {
                continue;
            }
            fileStorageService.delete(object.relativePath());
            log.debug("Deleted orphaned file {}", object.relativePath());
            deleted++;
        }

        if (deleted > 0) {
            log.info("Storage cleanup: deleted {} orphaned file(s) older than {}", deleted, minimumAge);
        }
    }

    /**
     * Все пути, на которые ссылается база. Между этим чтением и обходом диска состояние
     * может измениться в обе стороны, и от рассинхрона защищает не порядок шагов, а порог
     * возраста: всё, что появилось за время работы уборки, заведомо моложе суток. Строка,
     * удалённая посреди сверки, наоборот, оставит свой файл до следующего запуска — что как
     * раз безопасная сторона ошибки.
     */
    private Set<String> collectReferencedPaths() {
        Set<String> referenced = new HashSet<>();
        for (List<String> paths : List.of(attachmentRepository.findAllStoredPaths(),
                userRepository.findAllAvatarPaths(),
                projectRepository.findAllPreviewImagePaths())) {
            referenced.addAll(paths);
        }
        return referenced;
    }
}
