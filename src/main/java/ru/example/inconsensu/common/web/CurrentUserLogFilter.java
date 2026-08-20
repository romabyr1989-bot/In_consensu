package ru.example.inconsensu.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.example.inconsensu.common.security.CurrentUser;

/**
 * Кладёт в MDC того, кто выполняет запрос (NFR-6).
 *
 * <p>Отдельный фильтр, а не дополнение к {@link RequestIdFilter}: тот работает первым, до цепочки
 * безопасности, и на его этапе пользователь ещё неизвестен. Этот идёт последним — после аутентификации.
 *
 * <p>В журнал попадает логин сотрудника, а не данные субъекта: NFR-3 запрещает ПДн в логах.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class CurrentUserLogFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put(MDC_KEY, CurrentUser.login());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
