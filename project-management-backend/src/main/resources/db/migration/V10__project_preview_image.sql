-- Квадратная превью-картинка проекта, показывается в списке проектов (ProjectCard).
-- Тот же паттерн, что и users.avatar_path: относительный путь на диске, генерируемый
-- FileStorageService (см. ProjectService.uploadPreviewImage), не публичный URL.
ALTER TABLE projects ADD COLUMN preview_image_path VARCHAR(500);
