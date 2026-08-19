package ru.example.inconsensu.iam.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.common.api.PageResponse;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.iam.application.UserService;

/** §9: управление пользователями (FR-11.1, FR-11.2). Доступно только роли ADMIN по Приложению E. */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    public record CreateUserRequest(
            @NotBlank @Size(max = 128) String login,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Size(max = 255) String fullName,
            @Email @Size(max = 255) String email,
            @NotEmpty Set<String> roles) {}

    public record UpdateUserRequest(
            @NotBlank @Size(max = 255) String fullName,
            @Email @Size(max = 255) String email,
            @NotEmpty Set<String> roles,
            boolean active) {}

    public record ResetPasswordRequest(@NotBlank @Size(min = 12, max = 128) String password) {}

    private final UserService userService;
    private final InConsensuProperties properties;
    private final Clock clock;

    public UserController(UserService userService, InConsensuProperties properties, Clock clock) {
        this.userService = userService;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping
    public PageResponse<UserResponse> list(
            @PageableDefault(size = 20, sort = "login", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.of(
                userService.list(pageable), user -> UserResponse.of(user, properties.timezone(), clock.instant()));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return UserResponse.of(userService.get(id), properties.timezone(), clock.instant());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return UserResponse.of(
                userService.create(
                        request.login(), request.password(), request.fullName(), request.email(), request.roles()),
                properties.timezone(),
                clock.instant());
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.of(
                userService.update(id, request.fullName(), request.email(), request.roles(), request.active()),
                properties.timezone(),
                clock.instant());
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.password());
    }
}
