package ru.example.inconsensu.ui.infrastructure;

import java.time.Duration;
import org.springframework.boot.web.servlet.server.CookieSameSiteSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import ru.example.inconsensu.common.domain.RoleCode;

/**
 * Веб-интерфейс сотрудника и страница самообслуживания (UI-0.3, §16.2).
 *
 * <p>Отдельная цепочка от API: браузеру нужны сессия, CSRF и переход на страницу входа, а машинному
 * клиенту — stateless JWT и ProblemDetail вместо редиректа. Сервисная роль INTEGRATION в интерфейс
 * не пускается: у неё нет рабочего места, только токен.
 */
@Configuration
public class UiSecurityConfig {

    /** UI-0.3: таймаут неактивности сессии сотрудника. */
    public static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);

    public static final String LOGIN_PATH = "/ui/login";

    @Bean
    @Order(2)
    public SecurityFilterChain uiSecurityFilterChain(HttpSecurity http) throws Exception {
        SavedRequestAwareAuthenticationSuccessHandler successHandler =
                new SavedRequestAwareAuthenticationSuccessHandler();
        successHandler.setDefaultTargetUrl("/ui/");

        http.securityMatcher("/ui/**", "/self/ui/**", "/webjars/**", "/assets/**", "/favicon.ico")
                .authorizeHttpRequests(requests -> requests.requestMatchers(
                                "/webjars/**", "/assets/**", "/favicon.ico", LOGIN_PATH, "/ui/session-expired")
                        .permitAll()
                        // UI-18: страница клиента открывается по одноразовой ссылке, а не по учётной записи.
                        .requestMatchers("/self/ui/**")
                        .permitAll()
                        .requestMatchers("/ui/**")
                        .hasAnyRole(
                                RoleCode.ADMIN.name(),
                                RoleCode.DPO.name(),
                                RoleCode.LAWYER.name(),
                                RoleCode.MANAGER.name(),
                                RoleCode.MARKETING.name(),
                                RoleCode.AUDITOR.name())
                        .anyRequest()
                        .authenticated())
                .formLogin(form -> form.loginPage(LOGIN_PATH)
                        .loginProcessingUrl(LOGIN_PATH)
                        .successHandler(successHandler)
                        .failureUrl(LOGIN_PATH + "?error")
                        .permitAll())
                .logout(logout -> logout.logoutRequestMatcher(new AntPathRequestMatcher("/ui/logout"))
                        .logoutSuccessUrl(LOGIN_PATH + "?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .invalidSessionUrl("/ui/session-expired"))
                .exceptionHandling(handling -> handling.accessDeniedPage("/ui/forbidden"));

        return http.build();
    }

    /** UI-0.3: cookie сессии не должна уезжать на сторонние сайты. */
    @Bean
    public CookieSameSiteSupplier sessionCookieSameSiteSupplier() {
        return CookieSameSiteSupplier.ofLax();
    }
}
