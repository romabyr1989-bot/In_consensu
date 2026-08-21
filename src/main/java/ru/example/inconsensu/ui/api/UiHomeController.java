package ru.example.inconsensu.ui.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import ru.example.inconsensu.catalog.application.CatalogStatsService;
import ru.example.inconsensu.ui.application.UiDashboardService;

/** UI-1, UI-2, UI-17: вход, дашборд и служебные страницы. */
@Controller
public class UiHomeController {

    /** Тот же пароль, что заводит загрузчик демо-данных: вымышленные учётные записи профиля `demo`. */
    private static final String DEMO_PASSWORD =
            ru.example.inconsensu.integration.application.DemoDataLoader.DEMO_PASSWORD;

    private final UiDashboardService dashboard;
    private final org.springframework.core.env.Environment environment;

    public UiHomeController(UiDashboardService dashboard, org.springframework.core.env.Environment environment) {
        this.dashboard = dashboard;
        this.environment = environment;
    }

    /**
     * Страница входа (UI-1).
     *
     * <p>В демонстрационном профиле поля заполняются заранее и рядом перечислены учётные записи всех
     * ролей: демо существует, чтобы его смотрели, а не подбирали пароль. Подстановка строго под профилем
     * `demo` — в эксплуатации пароль в разметке недопустим (NFR-3), поэтому проверяется активный профиль,
     * а не настройка, которую можно случайно включить.
     */
    @GetMapping("/ui/login")
    public String login(Model model) {
        // UI-1: кнопка входа через IdP показывается только при включённом профиле oidc. Признака в модели
        // не было вовсе, поэтому кнопка не появлялась ни при каком профиле.
        model.addAttribute("oidcEnabled", environment.matchesProfiles("oidc"));
        if (environment.matchesProfiles("demo")) {
            model.addAttribute(
                    "demoLogin",
                    ru.example.inconsensu.common.domain.RoleCode.ADMIN.name().toLowerCase(java.util.Locale.ROOT));
            model.addAttribute("demoPassword", DEMO_PASSWORD);
            // INTEGRATION в подсказку не попадает: по UI-0.3 у служебной роли нет рабочего места,
            // и предлагать её логин на странице входа значит звать в тупик.
            model.addAttribute(
                    "demoRoles",
                    java.util.Arrays.stream(ru.example.inconsensu.common.domain.RoleCode.values())
                            .filter(role -> role != ru.example.inconsensu.common.domain.RoleCode.INTEGRATION)
                            .toList());
        }
        return "ui/login";
    }

    @GetMapping({"/ui", "/ui/"})
    @PreAuthorize("isAuthenticated()")
    public String home(Model model) {
        CatalogStatsService.CatalogStats stats = dashboard.stats();
        model.addAttribute("stats", stats);
        model.addAttribute("recentNotifications", dashboard.recentNotifications());
        model.addAttribute("failedDeliveries", dashboard.failedDeliveries());
        model.addAttribute("failedImports", dashboard.failedImports());
        return "ui/dashboard";
    }

    /** UI-17: страница «недостаточно прав» обязана отвечать 403, а не показывать 200 с текстом отказа. */
    @GetMapping("/ui/forbidden")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String forbidden() {
        return "ui/error/forbidden";
    }

    /**
     * UI-0.3: после входа сотрудник возвращается туда, где его застало истечение сессии.
     *
     * <p>Адрес приходит параметром от стратегии истёкшей сессии: до фильтра, сохраняющего запрос, дело не
     * доходит, и без параметра ссылка «Войти» вела бы на главную, обещая на странице обратное.
     */
    /**
     * Неизвестный адрес интерфейса (UI-17).
     *
     * <p>Без такого маршрута запрос уходил к обработчику статики, а оттуда — в общий обработчик машинной
     * цепочки, и сотрудник получал 500 с телом ProblemDetail. Шаблон «/ui/**» самый общий, поэтому
     * настоящие экраны по-прежнему выигрывают сопоставление.
     */
    @GetMapping("/ui/**")
    public String unknownPage() {
        throw ru.example.inconsensu.common.error.ApiException.notFound("Страница не найдена");
    }

    @GetMapping("/ui/session-expired")
    public String sessionExpired(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String from, Model model) {
        // Только внутренние адреса: параметр приходит из запроса, и «//example.com» увёл бы на чужой сайт.
        model.addAttribute(
                "returnTo", from != null && from.startsWith("/ui/") && !from.startsWith("//") ? from : "/ui/");
        return "ui/error/session-expired";
    }
}
