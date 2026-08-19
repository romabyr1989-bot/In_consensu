package ru.example.inconsensu.support;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Выполняет код от имени сотрудника с указанными ролями.
 *
 * <p>Нужно там, где сценарий вызывает application-сервисы напрямую: проверки ролей и запись актора в аудит
 * читают SecurityContext, который в тесте пуст.
 */
public final class RunAs {

    private RunAs() {}

    public static <T> T roles(String login, List<String> roles, Supplier<T> action) {
        var authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        var authentication = new UsernamePasswordAuthenticationToken(login, "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    public static void rolesVoid(String login, List<String> roles, Runnable action) {
        roles(login, roles, () -> {
            action.run();
            return null;
        });
    }
}
