package ru.example.inconsensu.ui.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.server.CookieSameSiteSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
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
                    protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response) {
                        // Адрес возврата приходит из формы: сохранённого запроса после истечения сессии нет.
                        // Проверка обязательна — «//evil.example» увела бы сотрудника на чужой сайт.
                        String requested = request.getParameter(RETURN_PARAMETER);
                        if (requested != null
                                && (requested.startsWith("/ui/") || requested.startsWith("/app/"))
                                && !requested.startsWith("//")) {
                            return requested;
                        }
                        return super.determineTargetUrl(request, response);
                    }
                };
        // Рабочее место сотрудника — одностраничное приложение (ADR-0087). Прежние страницы остаются
        // только до удаления, и приводить сотрудника после входа именно на них — значит показывать ему
        // старый интерфейс вместо нового.
        successHandler.setDefaultTargetUrl("/app/");

        // `/app/**` — одностраничное приложение (ADR-0087): та же сессия и та же матрица ролей, что у /ui.
        http.securityMatcher("/ui/**", "/app/**", "/self/ui/**", "/webjars/**", "/assets/**", "/favicon.ico")
                .authorizeHttpRequests(requests -> requests.requestMatchers(
                                "/webjars/**", "/assets/**", "/favicon.ico", LOGIN_PATH, "/ui/session-expired")
                        .permitAll()
                        // UI-18: страница клиента открывается по одноразовой ссылке, а не по учётной записи.
                        .requestMatchers("/self/ui/**")
                        .permitAll()
                        .requestMatchers("/ui/**", "/app/**")
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
                // UI-0.3, ADR-0087: токен CSRF отдаётся кукой, доступной сценарию. Иначе одностраничное
                // приложение не может подписать запрос: токен, лежащий в сессии, оно не видит, а формы
                // Thymeleaf работают одинаково при обоих хранилищах. Кука не заменяет сессию и не является
                // учётными данными — сама сессия остаётся HttpOnly.
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(eagerCsrfToken()))
                // Отказ по адресу данных не должен приходить страницей: приложение ждёт JSON, а получало
                // перенаправление на HTML и показало бы вместо ошибки кусок вёрстки (UI-0.9). Экраны
                // Thymeleaf по-прежнему получают страницу «Доступ закрыт».
                .exceptionHandling(
                        handling -> handling.accessDeniedHandler(accessDenied()).authenticationEntryPoint(entryPoint()))
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
    private void frameAncestors(HttpServletRequest request, HttpServletResponse response) {
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
    /**
     * Отказ в правах: JSON для адресов данных, страница — для экранов.
     *
     * <p>Разветвление собирается вручную: `accessDeniedPage` задаёт обработчик целиком и отменяет
     * сопоставление по адресу, поэтому отказ на `/ui/api` уходил перенаправлением.
     */
    private static org.springframework.security.web.access.AccessDeniedHandler accessDenied() {
        var page = new org.springframework.security.web.access.AccessDeniedHandlerImpl();
        page.setErrorPage("/ui/forbidden");
        return new org.springframework.security.web.access.RequestMatcherDelegatingAccessDeniedHandler(
                new java.util.LinkedHashMap<>(java.util.Map.of(
                        (org.springframework.security.web.util.matcher.RequestMatcher) UiSecurityConfig::isDataRequest,
                        (org.springframework.security.web.access.AccessDeniedHandler) UiSecurityConfig::denyAsJson)),
                page);
    }

    /**
     * Вход без сессии: JSON для адресов данных, переход на страницу входа — для экранов.
     *
     * <p>Разветвление собирается вручную по той же причине, что и отказ в правах: одиночное сопоставление
     * Spring применяет ко всем запросам, и переход на страницу входа заменялся бы ответом 401.
     */
    private static org.springframework.security.web.AuthenticationEntryPoint entryPoint() {
        var delegating = new org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint(
                new java.util.LinkedHashMap<>(java.util.Map.of(
                        (org.springframework.security.web.util.matcher.RequestMatcher) UiSecurityConfig::isDataRequest,
                        (org.springframework.security.web.AuthenticationEntryPoint)
                                UiSecurityConfig::unauthorizedAsJson)));
        delegating.setDefaultEntryPoint(
                new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint(LOGIN_PATH));
        return delegating;
    }

    /** Адрес данных рабочего места: с него приложение ждёт JSON, а не страницу. */
    private static boolean isDataRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith(API_PREFIX);
    }

    private static final String API_PREFIX = "/ui/api/";

    /**
     * Отказ по правам — 403 с телом RFC 9457.
     *
     * <p>Причина не уточняется: сообщать, чего именно не хватает, значит рассказывать о правах чужой роли.
     */
    private static void denyAsJson(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException failure)
            throws IOException {
        problem(
                response,
                ru.example.inconsensu.common.error.ErrorCode.ACCESS_DENIED,
                "У вашей роли нет прав на операцию");
    }

    /** Сессия кончилась или её не было: приложение само отправит сотрудника на страницу входа. */
    private static void unauthorizedAsJson(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.core.AuthenticationException failure)
            throws IOException {
        problem(response, ru.example.inconsensu.common.error.ErrorCode.UNAUTHORIZED, "Сессия истекла, войдите заново");
    }

    /** Код отказа берётся из общего перечня: иначе на одно и то же отказывающее правило было бы два кода. */
    private static void problem(
            HttpServletResponse response, ru.example.inconsensu.common.error.ErrorCode code, String title)
            throws IOException {
        int status = code.status().value();
        response.setStatus(status);
        response.setContentType("application/problem+json;charset=UTF-8");
        response.getWriter()
                .write("{\"type\":\"%s\",\"title\":\"%s\",\"status\":%d}".formatted(code.type(), title, status));
    }

    /**
     * Токен CSRF выдаётся на каждый ответ, а не по требованию.
     *
     * <p>По умолчанию токен создаётся лениво: страница Thymeleaf запрашивает его сама, подставляя в форму, и
     * кука появляется заодно. Оболочка одностраничного приложения — статический файл, она ничего не
     * запрашивает, поэтому после входа кука оставалась удалённой (смена сессии её гасит) и первый же POST
     * уходил без подписи. Раннее чтение возвращает куку в каждый ответ.
     */
    private static CsrfTokenRequestAttributeHandler eagerCsrfToken() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    private static void sessionExpired(HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        // Приложение ждёт код, а не переход: страница входа в ответе на запрос данных выглядела бы как
        // успешный ответ с чужой вёрсткой. Тем же путём сюда попадает запрос без токена CSRF — Spring
        // считает его признаком истёкшей сессии.
        if (isDataRequest(request)) {
            problem(
                    response,
                    ru.example.inconsensu.common.error.ErrorCode.UNAUTHORIZED,
                    "Сессия истекла, войдите заново");
            return;
        }
        request.getSession(true);

        String path = request.getRequestURI() == null ? "" : request.getRequestURI();
        if (path.startsWith("/ui/session-expired") || path.startsWith(LOGIN_PATH)) {
            response.sendRedirect(LOGIN_PATH);
            return;
        }

        String target = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        if (target.length() > MAX_RETURN_LENGTH) {
            target = "/app/";
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
