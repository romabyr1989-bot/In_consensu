package ru.example.inconsensu.ui.infrastructure;

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

    public static final String LOGIN_PATH = "/ui/login";

    /** Имя параметра с адресом возврата: он же в форме входа и на странице «Сессия истекла». */
    public static final String RETURN_PARAMETER = "from";

    private final ru.example.inconsensu.iam.application.AuthService authService;
    private final String frameAncestors;

    public UiSecurityConfig(
            ru.example.inconsensu.iam.application.AuthService authService,
            @org.springframework.beans.factory.annotation.Value("${inconsensu.selfservice.frame-ancestors:'self'}")
                    String frameAncestors) {
        this.authService = authService;
        this.frameAncestors = frameAncestors;
    }

    @Bean
    @Order(2)
    public SecurityFilterChain uiSecurityFilterChain(HttpSecurity http) throws Exception {
        SavedRequestAwareAuthenticationSuccessHandler successHandler =
                new SavedRequestAwareAuthenticationSuccessHandler() {
                    @Override
                    protected String determineTargetUrl(
                            jakarta.servlet.http.HttpServletRequest request,
                            jakarta.servlet.http.HttpServletResponse response) {
                        // Адрес возврата приходит из формы: сохранённого запроса после истечения сессии нет.
                        // Проверка обязательна — «//evil.example» увела бы сотрудника на чужой сайт.
                        String requested = request.getParameter(RETURN_PARAMETER);
                        if (requested != null && requested.startsWith("/ui/") && !requested.startsWith("//")) {
                            return requested;
                        }
                        return super.determineTargetUrl(request, response);
                    }
                };
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
                        .failureHandler(failureHandler())
                        .permitAll())
                .logout(logout -> logout.logoutRequestMatcher(new AntPathRequestMatcher("/ui/logout"))
                        .logoutSuccessUrl(LOGIN_PATH + "?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // UI-0.3: после входа сотрудник возвращается туда, где его застало истечение сессии.
                        // invalidSessionUrl отдаёт голый переход, поэтому адрес переносится параметром.
                        .invalidSessionStrategy(UiSecurityConfig::sessionExpired))
                .exceptionHandling(handling -> handling.accessDeniedPage("/ui/forbidden"))
                // UI-18: страница самообслуживания встраивается в личный кабинет клиента, поэтому запрет
                // фреймов с неё снимается, а разрешённые источники задаёт оператор настройкой
                // INCONSENSU_SELFSERVICE_FRAME_ANCESTORS. Экраны сотрудника остаются закрытыми от фреймов.
                .headers(headers ->
                        headers.frameOptions(frame -> frame.disable()).addHeaderWriter(this::frameAncestors));

        return http.build();
    }

    /**
     * Заголовки фреймов: страница клиента встраивается, экраны сотрудника — нет (UI-18, UI-0.3).
     *
     * <p>Политика задаётся настройкой оператора: по умолчанию встраивание разрешено только тому же
     * источнику, а личный кабинет клиента добавляется явно — иначе страницу с согласиями можно было бы
     * открыть во фрейме на чужом сайте.
     */
    private void frameAncestors(
            jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/self/ui")) {
            response.setHeader("Content-Security-Policy", "frame-ancestors " + frameAncestors);
        } else {
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        }
    }

    /** Длина адреса возврата: длиннее — переход на главную, иначе Location раздувает заголовки ответа. */
    private static final int MAX_RETURN_LENGTH = 512;

    /**
     * Куда после истечения сессии: страница объяснения и адрес, на который вернуть после входа (UI-17).
     *
     * <p>Браузер с мёртвым идентификатором сессии — обычное дело после перезапуска службы. Раньше на каждый
     * такой запрос выдавался переход на `/ui/session-expired?from=<текущий адрес>`, а сам этот переход
     * приходил с тем же мёртвым идентификатором: адрес вкладывался сам в себя и рос при каждом круге, пока
     * заголовок `Location` не переставал помещаться в буфер. Вкладка при этом оставалась пустой, а в адресной
     * строке висел бесконечный URL.
     *
     * <p>Поэтому сессия выдаётся сразу: браузер получает новый идентификатор и следующий запрос уже не
     * считается «истёкшим». Страницы входа и объяснения возврата к себе не требуют.
     */
    private static void sessionExpired(
            jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        request.getSession(true);

        String path = request.getRequestURI() == null ? "" : request.getRequestURI();
        if (path.startsWith("/ui/session-expired") || path.startsWith(LOGIN_PATH)) {
            response.sendRedirect(LOGIN_PATH);
            return;
        }

        String target = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        if (target.length() > MAX_RETURN_LENGTH) {
            target = "/ui/";
        }
        response.sendRedirect("/ui/session-expired?from="
                + java.net.URLEncoder.encode(target, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * UI-1: заблокированной учётной записи сообщается, через сколько можно повторить.
     *
     * <p>Раньше стоял фиксированный переход на `?error`, поэтому ветка «слишком много попыток» на странице
     * входа была недостижима, а сотрудник видел «неверный логин или пароль» и продолжал подбирать.
     */
    private org.springframework.security.web.authentication.AuthenticationFailureHandler failureHandler() {
        return (request, response, exception) -> {
            String target = LOGIN_PATH + "?error";
            if (exception instanceof org.springframework.security.authentication.LockedException) {
                target = LOGIN_PATH + "?error&locked&minutes="
                        + authService.lockMinutesLeft(request.getParameter("username"));
            }
            new org.springframework.security.web.DefaultRedirectStrategy().sendRedirect(request, response, target);
        };
    }

    /**
     * UI-0.3: cookie сессии не должна уезжать на сторонние сайты.
     *
     * <p>Остальные атрибуты — HttpOnly, Secure и таймаут неактивности — заданы настройками
     * {@code server.servlet.session} в application.yml: там их видно оператору, а не только в коде.
     */
    @Bean
    public CookieSameSiteSupplier sessionCookieSameSiteSupplier() {
        return CookieSameSiteSupplier.ofLax();
    }
}
