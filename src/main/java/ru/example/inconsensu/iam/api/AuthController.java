package ru.example.inconsensu.iam.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.iam.application.AuthService;

/** §9: вход и обновление токена (FR-11.1). Единственные эндпоинты API, доступные без аутентификации. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String login, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record TokenResponse(
            String accessToken, String refreshToken, String tokenType, long expiresIn, String login) {

        static TokenResponse of(AuthService.AuthTokens tokens) {
            return new TokenResponse(
                    tokens.accessToken(), tokens.refreshToken(), "Bearer", tokens.expiresInSeconds(), tokens.login());
        }
    }

    private final AuthService authService;
    private final ru.example.inconsensu.iam.application.LoginRateLimiter rateLimiter;

    public AuthController(AuthService authService, ru.example.inconsensu.iam.application.LoginRateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * FR-11.1: вход защищён и блокировкой учётной записи, и ограничением частоты с одного адреса.
     *
     * <p>Блокировка защищает конкретного сотрудника, но перебор логинов ею не останавливается: неудачи
     * считаются по адресу обратившегося, а удачный вход счётчик не наращивает.
     */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, jakarta.servlet.http.HttpServletRequest http) {
        String source = http.getRemoteAddr();
        rateLimiter.check(source);
        try {
            return TokenResponse.of(authService.login(request.login(), request.password()));
        } catch (ru.example.inconsensu.common.error.ApiException rejected) {
            rateLimiter.registerFailure(source);
            throw rejected;
        }
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.of(authService.refresh(request.refreshToken()));
    }
}
