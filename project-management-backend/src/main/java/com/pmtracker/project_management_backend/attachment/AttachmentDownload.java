package com.pmtracker.project_management_backend.attachment;

import org.springframework.core.io.Resource;

public record AttachmentDownload(Resource resource, String originalFilename, String contentType) {
}
