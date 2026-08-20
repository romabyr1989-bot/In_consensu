package ru.example.inconsensu.iam.application;

import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.iam.infrastructure.AppUserRepository;

/**
 * Счётчик неудачных входов через форму интерфейса (FR-11.1, UI-1).
 *
 * <p>REST-вход считает попытки сам, потому что сверяет пароль вручную и событий Spring Security не
 * публикует. Форма же обрабатывается стандартным провайдером, который только читает признак блокировки и
 * ничего не наращивает, — через `/ui/login` пароль можно было подбирать без ограничений.
 *
 * <p>Слушатель реагирует только на вход логином и паролем: события проверки JWT к перебору отношения не
 * имеют, а их учёт сбрасывал бы счётчик на каждом запросе к API.
 */
@Component
public class FormLoginAttemptListener {

    private final AppUserRepository users;
    private final InConsensuProperties properties;
    private final Clock clock;

    public FormLoginAttemptListener(AppUserRepository users, InConsensuProperties properties, Clock clock) {
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        if (!(event.getAuthentication() instanceof UsernamePasswordAuthenticationToken token)) {
            return;
        }
        users.findByLoginIgnoreCase(String.valueOf(token.getPrincipal())).ifPresent(user -> {
            user.registerFailedLogin(
                    properties.security().login().maxFailedAttempts(),
                    clock.instant(),
                    properties.security().login().lockDuration());
            users.save(user);
        });
    }

    @EventListener
    @Transactional
    public void onSuccess(AuthenticationSuccessEvent event) {
        if (!(event.getAuthentication() instanceof UsernamePasswordAuthenticationToken token)) {
            return;
        }
        users.findByLoginIgnoreCase(token.getName()).ifPresent((AppUser user) -> {
            user.registerSuccessfulLogin(clock.instant());
            users.save(user);
        });
    }
}
