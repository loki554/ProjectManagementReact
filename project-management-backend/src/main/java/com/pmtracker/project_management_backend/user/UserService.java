package com.pmtracker.project_management_backend.user;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.RefreshTokenRepository;
import com.pmtracker.project_management_backend.auth.UserRepository;
import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.common.exception.InvalidCurrentPasswordException;
import com.pmtracker.project_management_backend.common.exception.InvalidFileException;
import com.pmtracker.project_management_backend.common.exception.ResourceNotFoundException;
import com.pmtracker.project_management_backend.storage.FileStorageService;
import com.pmtracker.project_management_backend.storage.FileTypeValidator;
import com.pmtracker.project_management_backend.storage.ImageSanitizer;
import com.pmtracker.project_management_backend.storage.SanitizedImage;
import com.pmtracker.project_management_backend.storage.StoredFile;
import com.pmtracker.project_management_backend.user.dto.ChangePasswordRequest;
import com.pmtracker.project_management_backend.user.dto.UpdateProfileRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final Set<String> ALLOWED_AVATAR_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    // Глобальный spring.servlet.multipart.max-file-size теперь 20MB (поднят ради вложений
    // к задачам, см. AttachmentService) — без явной проверки здесь аватарка расширилась бы
    // до того же лимита, хотя ей для картинки профиля хватает 5MB.
    private static final long MAX_AVATAR_SIZE_BYTES = 5L * 1024 * 1024;

    // Аватарка показывается размером в несколько десятков пикселей; 512 — запас под retina и
    // будущие крупные карточки. Всё, что больше, ужимается при перекодировании (ImageSanitizer):
    // хранить на диске 4000x3000 ради кружка в шапке незачем.
    private static final int MAX_AVATAR_DIMENSION = 512;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;
    private final FileTypeValidator fileTypeValidator;
    private final ImageSanitizer imageSanitizer;

    public UserService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       FileStorageService fileStorageService,
                       FileTypeValidator fileTypeValidator,
                       ImageSanitizer imageSanitizer) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.fileStorageService = fileStorageService;
        this.fileTypeValidator = fileTypeValidator;
        this.imageSanitizer = imageSanitizer;
    }

    /**
     * Смена пароля изнутри аккаунта. Текущий пароль спрашиваем не для проформы: access-токен
     * живёт 15 минут и вполне может быть у того, кто увёл чужой незалоченный ноутбук, — без этой
     * проверки такой человек молча меняет пароль и запирает владельца снаружи.
     *
     * Все refresh-токены после смены гасим, включая токен той сессии, из которой пришёл запрос:
     * смысл смены пароля в том, чтобы выкинуть чужие сессии, а вычислить «свою» здесь нечем —
     * запрос авторизован access-токеном, refresh-токен в нём не участвует. Фронтенд на успешный
     * ответ разлогинивается сам и просит войти заново (см. ProfilePage).
     */
    @Transactional
    public void changePassword(User user, ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidCurrentPasswordException();
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        int revokedCount = refreshTokenRepository.revokeAllByUserId(user.getId());
        log.info("Password changed for user {}, revoked {} active refresh token(s)", user.getId(), revokedCount);
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
            throw new InvalidFileException("No file selected");
        }
        if (file.getSize() > MAX_AVATAR_SIZE_BYTES) {
            throw new InvalidFileException("File is too large (max 5MB)");
        }
        if (!ALLOWED_AVATAR_CONTENT_TYPES.contains(file.getContentType())) {
            throw new InvalidFileException("Allowed image formats: PNG, JPEG, WEBP, GIF");
        }
        fileTypeValidator.requireContentMatchesDeclaredType(file);

        // На диск ложится не то, что прислал клиент, а результат перекодирования: исходные байты
        // (вместе с возможным полиглотным хвостом и EXIF-геолокацией) до хранилища не доезжают
        // вообще. См. 1.12 IMPROVEMENTS.md.
        SanitizedImage image = imageSanitizer.sanitize(file, MAX_AVATAR_DIMENSION);

        String previousAvatarPath = user.getAvatarPath();

        StoredFile stored;
        try {
            stored = fileStorageService.store(image.content(), image.extension(), "avatars/" + user.getId());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save avatar file", e);
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
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getAvatarPath() == null) {
            throw new ResourceNotFoundException("User has no avatar");
        }
        try {
            return fileStorageService.load(user.getAvatarPath());
        } catch (NoSuchElementException e) {
            // запись в БД есть, а файла на диске уже нет — не должно происходить в норме,
            // но лучше вернуть понятный 404, чем уронить запрос в 500
            throw new ResourceNotFoundException("Avatar file not found");
        }
    }
}
