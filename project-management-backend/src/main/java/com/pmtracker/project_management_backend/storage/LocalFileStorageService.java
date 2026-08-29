package com.pmtracker.project_management_backend.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path basePath;

    public LocalFileStorageService(@Value("${app.storage.base-path}") String basePathProperty) {
        this.basePath = Paths.get(basePathProperty).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(MultipartFile file, String subdirectory) throws IOException {
        Path targetDir = resolveWithinBase(subdirectory);
        Files.createDirectories(targetDir);

        // Имя файла всегда генерируем сами (UUID + безопасное расширение) — не используем
        // оригинальное имя от клиента напрямую даже частично, чтобы не открывать path traversal
        // через что-то вроде "../../../etc/passwd.png" в качестве originalFilename.
        String extension = extractSafeExtension(file.getOriginalFilename());
        Path targetFile = targetDir.resolve(UUID.randomUUID() + extension);

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }

        return new StoredFile(toRelativePath(targetFile), Files.size(targetFile));
    }

    @Override
    public StoredFile store(byte[] content, String extension, String subdirectory) throws IOException {
        Path targetDir = resolveWithinBase(subdirectory);
        Files.createDirectories(targetDir);

        // extension приходит из кода (ImageSanitizer), а не от клиента, но прогоняем через ту же
        // проверку: единственный способ гарантировать, что в имени не окажется ничего лишнего.
        Path targetFile = targetDir.resolve(UUID.randomUUID() + sanitizeExtension(extension));
        Files.write(targetFile, content);

        return new StoredFile(toRelativePath(targetFile), Files.size(targetFile));
    }

    @Override
    public Resource load(String relativePath) {
        Path file = resolveWithinBase(relativePath);
        Resource resource = new FileSystemResource(file);
        if (!resource.exists() || !resource.isReadable()) {
            throw new NoSuchElementException("File not found: " + relativePath);
        }
        return resource;
    }

    @Override
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolveWithinBase(relativePath));
        } catch (IOException e) {
            // best-effort: не удалённый старый файл — не повод ронять основной запрос
        }
    }

    /**
     * Обход всего дерева хранилища. Каталог может не существовать вовсе — на свежей машине
     * до первой загрузки его никто не создаёт, и это не ошибка, а пустое хранилище.
     */
    @Override
    public List<StoredObject> listAll() {
        if (!Files.isDirectory(basePath)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(basePath)) {
            return files.filter(Files::isRegularFile)
                    .map(this::toStoredObject)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list the storage directory", e);
        }
    }

    /**
     * Файл, исчезнувший между обходом каталога и чтением его атрибутов, — это норма, а не
     * сбой: рядом работает удаление вложений. Пропускаем такой файл вместо того, чтобы
     * ронять всю сверку.
     */
    private Optional<StoredObject> toStoredObject(Path file) {
        try {
            return Optional.of(new StoredObject(toRelativePath(file), Files.getLastModifiedTime(file).toInstant()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path resolveWithinBase(String relativePath) {
        Path resolved = basePath.resolve(relativePath).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new UncheckedIOException(new IOException("Path escapes storage root: " + relativePath));
        }
        return resolved;
    }

    private String toRelativePath(Path targetFile) {
        return basePath.relativize(targetFile).toString().replace('\\', '/');
    }

    private String extractSafeExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0) {
            return "";
        }
        return sanitizeExtension(originalFilename.substring(dotIndex));
    }

    private String sanitizeExtension(String extension) {
        String normalized = extension.toLowerCase();
        return normalized.matches("\\.[a-z0-9]{1,5}") ? normalized : "";
    }
}
