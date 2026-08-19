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

    private final UiDashboardService dashboard;

    public UiHomeController(UiDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/ui/login")
    public String login() {
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

    @GetMapping("/ui/session-expired")
    public String sessionExpired() {
        return "ui/error/session-expired";
    }
}
