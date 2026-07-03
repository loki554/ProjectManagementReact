package com.pmtracker.project_management_backend.user;

import com.pmtracker.project_management_backend.auth.User;
import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.user.dto.UpdateProfileRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // GET без обращения к сервису: JwtAuthenticationFilter уже подгрузил полноценного
    // User из БД в SecurityContext, тут просто нечего кроме маппинга в DTO делать.
    @GetMapping("/me")
    public ResponseEntity<UserSummary> me(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(UserSummary.from(currentUser));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserSummary> updateMe(@AuthenticationPrincipal User currentUser,
                                                 @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(currentUser, request));
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserSummary> uploadAvatar(@AuthenticationPrincipal User currentUser,
                                                      @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(userService.uploadAvatar(currentUser, file));
    }

    // Отдача аватарки требует авторизации (любой залогиненный пользователь может посмотреть
    // чужую аватарку — это не чувствительные данные), но не хостится статикой напрямую,
    // чтобы путь к файлу на диске не был угадываемым/публичным.
    @GetMapping("/{id}/avatar")
    public ResponseEntity<Resource> getAvatar(@PathVariable UUID id) {
        Resource resource = userService.getAvatarResource(id);
        MediaType contentType = resolveContentType(resource);
        return ResponseEntity.ok().contentType(contentType).body(resource);
    }

    private MediaType resolveContentType(Resource resource) {
        try {
            String probed = Files.probeContentType(resource.getFile().toPath());
            return probed != null ? MediaType.parseMediaType(probed) : MediaType.APPLICATION_OCTET_STREAM;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
