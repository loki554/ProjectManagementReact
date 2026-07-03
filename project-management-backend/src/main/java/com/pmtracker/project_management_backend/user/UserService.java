package com.pmtracker.project_management_backend.user;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.common.exception.InvalidFileException;
import com.pmtracker.project_management_backend.common.exception.ResourceNotFoundException;
import com.pmtracker.project_management_backend.storage.FileStorageService;
import com.pmtracker.project_management_backend.storage.StoredFile;
import com.pmtracker.project_management_backend.user.dto.UpdateProfileRequest;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class UserService {

    private static final Set<String> ALLOWED_AVATAR_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;

    public UserService(UserRepository userRepository, FileStorageService fileStorageService) {
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
    }

    @Transactional
    public UserSummary updateProfile(User user, UpdateProfileRequest request) {
        user.setLastName(request.lastName());
        user.setFirstName(request.firstName());
        user.setPatronymic(request.patronymic());
        userRepository.save(user);
        return UserSummary.from(user);
    }

    @Transactional
    public UserSummary uploadAvatar(User user, MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidFileException("Файл не выбран");
        }
        if (!ALLOWED_AVATAR_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidFileException("Допустимые форматы изображения: PNG, JPEG, WEBP, GIF");
        }

        String previousAvatarPath = user.getAvatarPath();

        StoredFile stored;
        try {
            stored = fileStorageService.store(file, "avatars/" + user.getId());
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить файл аватарки", e);
        }

        user.setAvatarPath(stored.relativePath());
        userRepository.save(user);

        // Удаляем старый файл только после того, как новый успешно сохранён и БД обновлена —
        // если что-то пойдёт не так раньше, пользователь не останется без аватарки вовсе.
        if (previousAvatarPath != null) {
            fileStorageService.delete(previousAvatarPath);
        }

        return UserSummary.from(user);
    }

    public Resource getAvatarResource(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Пользователь не найден"));
        if (user.getAvatarPath() == null) {
            throw new ResourceNotFoundException("У пользователя нет аватарки");
        }
        try {
            return fileStorageService.load(user.getAvatarPath());
        } catch (NoSuchElementException e) {
            // запись в БД есть, а файла на диске уже нет — не должно происходить в норме,
            // но лучше вернуть понятный 404, чем уронить запрос в 500
            throw new ResourceNotFoundException("Файл аватарки не найден");
        }
    }
}
