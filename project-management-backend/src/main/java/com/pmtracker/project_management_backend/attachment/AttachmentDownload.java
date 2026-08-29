package com.pmtracker.project_management_backend.attachment;

import org.springframework.core.io.Resource;

import java.net.URI;
import java.util.Optional;

/**
 * Вложение, готовое к отдаче. Ровно одно из двух полей содержательно: либо {@code resource}
 * — файл течёт через приложение, как раньше, либо {@code directUrl} — временная ссылка прямо
 * в объектное хранилище, и приложение только перенаправляет на неё (3.7).
 */
public record AttachmentDownload(Resource resource, Optional<URI> directUrl,
                                 String originalFilename, String contentType) {

    static AttachmentDownload proxied(Resource resource, String originalFilename, String contentType) {
        return new AttachmentDownload(resource, Optional.empty(), originalFilename, contentType);
    }

    static AttachmentDownload redirected(URI directUrl, String originalFilename, String contentType) {
        return new AttachmentDownload(null, Optional.of(directUrl), originalFilename, contentType);
    }
}
