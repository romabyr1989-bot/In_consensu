package ru.example.inconsensu.iam.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.audit.application.AuditService;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.iam.infrastructure.AppUserRepository;
import ru.example.inconsensu.iam.infrastructure.TokenService;

/**
 * Built-in authentication (FR-11.1).
 *
 * <p>Failures are deliberately indistinguishable: an unknown login, a wrong password and a disabled account all answer
 * the same way, so the endpoint cannot be used to enumerate employees (UI-1, NFR-3).
 */
@Service
public class AuthService {

    /** Aggregate name under which logins appear in the audit journal. */
    public static final String AGGREGATE_TYPE = "app_user";

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    public record AuthTokens(String accessToken, String refreshToken, long expiresInSeconds, String login) {}

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuditService auditService;
    private final InConsensuProperties properties;
    private final Clock clock;

    public AuthService(
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            AuditService auditService,
            InConsensuProperties properties,
            Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @implNote {@code noRollbackFor} is essential: rejecting a login throws, and a rollback would undo the very
     *     counter that FR-11.1 relies on, leaving the account unlockable no matter how many attempts are made.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthTokens login(String login, String rawPassword) {
        Instant now = clock.instant();
        Optional<AppUser> candidate = users.findByLoginIgnoreCase(login);

        if (candidate.isEmpty()) {
            // Still spend the time of a hash comparison: a fast "no such user" answer is an oracle.
            passwordEncoder.matches(rawPassword, "$2a$10$invalidinvalidinvalidinvalidinvalidinvalidinvalidinvalidi");
            throw invalidCredentials();
        }

        AppUser user = candidate.get();
        if (user.isLocked(now)) {
            long minutes =
                    Math.max(1, Duration.between(now, user.getLockedUntil()).toMinutes());
            throw new ApiException(
                    ErrorCode.TOO_MANY_REQUESTS, "Слишком много попыток входа. Повторите через " + minutes + " мин.");
        }
        if (!user.isActive() || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            user.registerFailedLogin(
                    properties.security().login().maxFailedAttempts(),
                    now,
                    properties.security().login().lockDuration());
            users.save(user);
            LOG.warn("Неудачная попытка входа, login={}", user.getLogin());
            throw invalidCredentials();
        }

        user.registerSuccessfulLogin(now);
        users.save(user);
        auditService.record(
                AGGREGATE_TYPE,
                user.getId().toString(),
                AuditEventType.LOGIN,
                Map.of("login", user.getLogin(), "roles", user.getRoleCodes()));

        return tokens(user);
    }

    /**
     * Сколько минут осталось до конца блокировки учётной записи; ноль — не заблокирована (FR-11.1, UI-1).
     *
     * <p>Нужна странице входа, чтобы сказать сотруднику срок. Модуль `ui` спрашивает через
     * application-сервис: смотреть в репозиторий чужого модуля запрещено §5.
     */
    @Transactional(readOnly = true)
    public long lockMinutesLeft(String login) {
        if (login == null || login.isBlank()) {
            return 0;
        }
        Instant now = clock.instant();
        return users.findByLoginIgnoreCase(login)
                .filter(user -> user.isLocked(now))
                .map(user -> Math.max(
                        1L, Duration.between(now, user.getLockedUntil()).toMinutes()))
                .orElse(0L);
    }

    @Transactional
    public AuthTokens refresh(String refreshToken) {
        String login;
        try {
            login = tokenService.loginFromRefreshToken(refreshToken);
        } catch (JwtException e) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Токен обновления недействителен или истёк");
        }
        AppUser user = users.findByLoginIgnoreCase(login)
                .filter(AppUser::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Учётная запись недоступна"));
        return tokens(user);
    }

    private AuthTokens tokens(AppUser user) {
        return new AuthTokens(
                tokenService.issueAccessToken(user),
                tokenService.issueRefreshToken(user),
                tokenService.accessTokenTtlSeconds(),
                user.getLogin());
    }

    private static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "Неверный логин или пароль");
    }
}
