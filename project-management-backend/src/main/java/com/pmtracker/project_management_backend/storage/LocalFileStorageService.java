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
import java.util.NoSuchElementException;
import java.util.UUID;

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

        String relativePath = basePath.relativize(targetFile).toString().replace('\\', '/');
        return new StoredFile(relativePath, Files.size(targetFile));
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

    private Path resolveWithinBase(String relativePath) {
        Path resolved = basePath.resolve(relativePath).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new UncheckedIOException(new IOException("Path escapes storage root: " + relativePath));
        }
        return resolved;
    }

    private String extractSafeExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0) {
            return "";
        }
        String extension = originalFilename.substring(dotIndex).toLowerCase();
        return extension.matches("\\.[a-z0-9]{1,5}") ? extension : "";
    }
}
