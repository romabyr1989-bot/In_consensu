package ru.example.cus.ui.api;

import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.example.cus.common.domain.RevocationSource;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.ui.application.UiSelfServiceViewService;

/**
 * UI-18: страница клиента, встраиваемая в личный кабинет.
 *
 * <p>Открывается по одноразовой ссылке и живёт ограниченное время: аутентификации сотрудника здесь нет,
 * поэтому доступ держится на идентификаторе сессии в HTTP-сессии браузера, а не на роли.
 */
@Controller
public class SelfUiController {

    private static final String SESSION_ATTRIBUTE = "cus.self.session";

    private final UiSelfServiceViewService view;

    public SelfUiController(UiSelfServiceViewService view) {
        this.view = view;
    }

    @GetMapping("/self/ui")
    public String page(@RequestParam(required = false) String token, HttpSession httpSession, Model model) {
        if (token != null && !token.isBlank()) {
            try {
                // Ссылка гасится сразу: адрес страницы уходит без токена, чтобы он не остался в истории браузера.
                UUID sessionId = view.open(token);
                httpSession.setAttribute(SESSION_ATTRIBUTE, sessionId);
                return "redirect:/self/ui";
            } catch (ApiException e) {
                // Просроченная или уже использованная ссылка — обычная ситуация для клиента, а не ошибка сервиса.
                return "ui/self/expired";
            }
        }
        UUID sessionId = (UUID) httpSession.getAttribute(SESSION_ATTRIBUTE);
        if (sessionId == null) {
            return "ui/self/expired";
        }
        try {
            model.addAttribute("page", view.page(sessionId));
            model.addAttribute("sources", RevocationSource.values());
            return "ui/self/consents";
        } catch (ApiException e) {
            httpSession.removeAttribute(SESSION_ATTRIBUTE);
            return "ui/self/expired";
        }
    }

    @PostMapping("/self/ui/consents/{id}/revoke")
    public String revoke(@PathVariable UUID id, HttpSession httpSession, Model model) {
        UUID sessionId = (UUID) httpSession.getAttribute(SESSION_ATTRIBUTE);
        if (sessionId == null) {
            return "ui/self/expired";
        }
        model.addAttribute("receipt", view.revoke(sessionId, id));
        model.addAttribute("page", view.page(sessionId));
        return "ui/self/consents";
    }

    @PostMapping("/self/ui/consents/revoke-all-advertising")
    public String revokeAllAdvertising(HttpSession httpSession, Model model) {
        UUID sessionId = (UUID) httpSession.getAttribute(SESSION_ATTRIBUTE);
        if (sessionId == null) {
            return "ui/self/expired";
        }
        model.addAttribute("receipt", view.revokeAllAdvertising(sessionId));
        model.addAttribute("page", view.page(sessionId));
        return "ui/self/consents";
    }

    /** Текст согласия открывается модальным окном — отдельным фрагментом, без ухода со страницы. */
    @GetMapping("/self/ui/consents/{id}/text")
    public String consentText(@PathVariable UUID id, HttpSession httpSession, Model model) {
        UUID sessionId = (UUID) httpSession.getAttribute(SESSION_ATTRIBUTE);
        if (sessionId == null) {
            return "ui/self/expired";
        }
        model.addAttribute("text", view.consentText(sessionId, id));
        return "ui/self/fragments :: consentText";
    }
}
