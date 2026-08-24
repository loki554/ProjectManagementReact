package com.pmtracker.project_management_backend.storage;

import com.pmtracker.project_management_backend.common.exception.InvalidFileException;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Проверяет, что содержимое загруженного файла действительно того типа, который заявил клиент.
 *
 * До этого единственной проверкой был {@code file.getContentType()} — то есть заголовок, который
 * клиент присылает сам и в котором может написать что угодно: положить HTML со скриптом под видом
 * {@code image/png} было тривиально (см. 1.12 IMPROVEMENTS.md). Здесь тип определяется по magic
 * bytes самого файла (Apache Tika) и сверяется с заявленным.
 */
@Component
public class FileTypeValidator {

    private static final Logger log = LoggerFactory.getLogger(FileTypeValidator.class);

    /**
     * Сигнатуры всех форматов, которые мы принимаем, укладываются в первые сотни байт; 64KB —
     * запас с большим избытком. Читаем в память только эту голову, а не файл целиком: вложение
     * может быть до 20MB, и держать его в heap ради опознания незачем.
     */
    private static final int DETECTION_HEAD_BYTES = 64 * 1024;

    /**
     * Заявленный тип → тип, который должны показать magic bytes. Совпадение проверяется с учётом
     * иерархии Tika (см. {@link MediaTypeRegistry#isSpecializationOf}), поэтому подтипы
     * засчитываются автоматически, и это здесь существенно:
     * <ul>
     *   <li>{@code application/x-tika-ooxml} — подтип {@code application/zip}, так что docx/xlsx
     *       проходят и как «архив»;</li>
     *   <li>{@code text/html}, {@code application/xml}, {@code image/svg+xml} — подтипы
     *       {@code text/plain}, так что настоящий текстовый файл не отбраковывается из-за того,
     *       что начинается с {@code <html>}. Опасности в этом нет: тип для хранения и выдачи
     *       берётся из заявленного (то есть {@code text/plain}), файл отдаётся с
     *       {@code Content-Disposition: attachment} и {@code X-Content-Type-Options: nosniff},
     *       поэтому браузер его не отрендерит.</li>
     * </ul>
     *
     * Имя файла детектору сознательно НЕ передаётся: Tika достраивает по расширению то, чего не
     * видно в сигнатуре, а расширение — такие же данные от клиента, как и Content-Type. Плата за
     * строгость — .doc и .xls (оба OLE2) неразличимы между собой без парсеров POI, как и
     * .docx с .xlsx (оба OOXML-архивы). Для нашей задачи это не важно: проверяем, что внутри
     * офисный контейнер, а не исполняемый файл; подменить один офисный формат другим — не атака.
     */
    private static final Map<String, MediaType> EXPECTED_DETECTED_TYPES = Map.ofEntries(
            Map.entry("image/png", MediaType.image("png")),
            Map.entry("image/jpeg", MediaType.image("jpeg")),
            Map.entry("image/webp", MediaType.image("webp")),
            Map.entry("image/gif", MediaType.image("gif")),
            Map.entry("application/pdf", MediaType.application("pdf")),
            Map.entry("application/msword", MediaType.application("x-tika-msoffice")),
            Map.entry("application/vnd.ms-excel", MediaType.application("x-tika-msoffice")),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    MediaType.application("x-tika-ooxml")),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    MediaType.application("x-tika-ooxml")),
            Map.entry("text/plain", MediaType.TEXT_PLAIN),
            Map.entry("application/zip", MediaType.APPLICATION_ZIP),
            Map.entry("application/x-zip-compressed", MediaType.APPLICATION_ZIP));

    private final Detector detector;
    private final MediaTypeRegistry mediaTypeRegistry;

    public FileTypeValidator() {
        TikaConfig config = TikaConfig.getDefaultConfig();
        this.detector = config.getDetector();
        this.mediaTypeRegistry = config.getMediaTypeRegistry();
    }

    /**
     * Вызывается ПОСЛЕ того, как вызывающий код убедился, что заявленный тип входит в его
     * whitelist: разные загрузки принимают разные наборы типов и сообщают об отказе по-своему,
     * а magic-bytes-проверка у всех одна.
     *
     * @throws InvalidFileException если содержимое не соответствует заявленному типу
     */
    public void requireContentMatchesDeclaredType(MultipartFile file) {
        String declaredType = normalize(file.getContentType());
        MediaType expected = EXPECTED_DETECTED_TYPES.get(declaredType);
        if (expected == null) {
            // сюда попадаем, только если whitelist вызывающего разошёлся с таблицей выше —
            // то есть при ошибке в коде, а не при плохом запросе. Отказ всё равно безопаснее
            // молчаливого пропуска: непроверяемый тип принимать нельзя.
            log.warn("No magic-byte signature configured for declared content type '{}' — rejecting upload",
                    declaredType);
            throw new InvalidFileException("Unsupported file type: " + declaredType);
        }

        MediaType detected = detect(file);
        if (!mediaTypeRegistry.isSpecializationOf(detected, expected) && !detected.equals(expected)) {
            throw new InvalidFileException("File content does not match its declared type: expected "
                    + declaredType + ", got " + detected);
        }
    }

    private MediaType detect(MultipartFile file) {
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(DETECTION_HEAD_BYTES);
        } catch (IOException e) {
            throw new InvalidFileException("Uploaded file could not be read");
        }
        try (InputStream in = new ByteArrayInputStream(head)) {
            // Metadata пустая намеренно — никаких подсказок от клиента, только байты, см. выше.
            return detector.detect(in, new Metadata());
        } catch (IOException e) {
            // ByteArrayInputStream не бросает IOException, но сигнатура Detector его объявляет
            throw new IllegalStateException("Unexpected failure detecting file type", e);
        }
    }

    private String normalize(String declaredContentType) {
        // "text/plain; charset=utf-8" от честного клиента — тот же text/plain.
        // parse() возвращает null на мусоре (и на null) — тогда отдаём заведомо неизвестный
        // тип, чтобы отказ пришёл из таблицы выше, а не из NPE.
        MediaType parsed = declaredContentType == null ? null : MediaType.parse(declaredContentType);
        return parsed == null ? "application/octet-stream" : parsed.getBaseType().toString();
    }
}
