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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return TokenResponse.of(authService.login(request.login(), request.password()));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.of(authService.refresh(request.refreshToken()));
    }
}
