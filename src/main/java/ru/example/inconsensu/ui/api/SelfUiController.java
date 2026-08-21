package ru.example.inconsensu.ui.api;

import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.ui.application.UiSelfServiceViewService;

/**
 * UI-18: страница клиента, встраиваемая в личный кабинет.
 *
 * <p>Открывается по одноразовой ссылке и живёт ограниченное время: аутентификации сотрудника здесь нет,
 * поэтому доступ держится на идентификаторе сессии в HTTP-сессии браузера, а не на роли.
 */
@Controller
public class SelfUiController {

    private static final String SESSION_ATTRIBUTE = "inconsensu.self.session";

    private final UiSelfServiceViewService view;
    private final ru.example.inconsensu.ui.application.UiBrandingService branding;

    public SelfUiController(
            UiSelfServiceViewService view, ru.example.inconsensu.ui.application.UiBrandingService branding) {
        this.view = view;
        this.branding = branding;
    }

    /**
     * UI-0.12: страница клиента отдаётся в брендировании оператора.
     *
     * <p>Каркас сотрудника сюда не подключается — у страницы своя разметка, — поэтому брендирование
     * кладётся в модель отдельно, в том числе на страницу «Ссылка недействительна».
     */
    @org.springframework.web.bind.annotation.ModelAttribute("branding")
    public ru.example.inconsensu.ui.application.UiBrandingService.Branding branding() {
        return branding.branding();
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

    /**
     * UI-18: первый шаг отзыва — окно с последствиями, второй шаг — кнопка в самом окне.
     *
     * <p>Раньше отзыв подтверждался одним вопросом браузера. Для клиента это необратимое действие, и ТЗ
     * требует двухшагового подтверждения: сначала показать, что именно погаснет, и только потом принять
     * решение.
     */
    @GetMapping("/self/ui/consents/{id}/revoke-dialog")
    public String revokeDialog(@PathVariable UUID id, HttpSession httpSession, Model model) {
        UUID sessionId = (UUID) httpSession.getAttribute(SESSION_ATTRIBUTE);
        if (sessionId == null) {
            return "ui/self/expired";
        }
        try {
            model.addAttribute("consent", view.revocable(sessionId, id));
            return "ui/self/fragments :: revokeDialog";
        } catch (ApiException alreadyGone) {
            // Окно грузится по HTMX, а он не подставляет ответы с ошибкой: клиент увидел бы, что кнопка
            // просто не работает. Поэтому объяснение приходит обычным фрагментом.
            model.addAttribute("message", alreadyGone.getMessage());
            return "ui/self/fragments :: dialogMessage";
        }
    }

    /** UI-18: то же подтверждение для отказа от всей рекламы — со списком того, что будет отозвано. */
    @GetMapping("/self/ui/consents/revoke-all-advertising-dialog")
    public String revokeAllAdvertisingDialog(HttpSession httpSession, Model model) {
        UUID sessionId = (UUID) httpSession.getAttribute(SESSION_ATTRIBUTE);
        if (sessionId == null) {
            return "ui/self/expired";
        }
        model.addAttribute("titles", view.advertisingTitles(sessionId));
        return "ui/self/fragments :: revokeAllDialog";
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
