package ru.example.inconsensu.ui.api;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.example.inconsensu.common.domain.CommunicationChannel;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RevocationSource;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.registry.application.RevocationService;
import ru.example.inconsensu.registry.application.SubjectCardService;
import ru.example.inconsensu.registry.domain.Subject;
import ru.example.inconsensu.ui.application.UiSearchCriteria;
import ru.example.inconsensu.ui.application.UiSubjectViewService;

/**
 * UI-3, UI-4, UI-5: поиск клиента, карточка и отзыв согласия.
 *
 * <p>Вкладки и диалог отзыва обновляются фрагментами HTMX: по UI-4 карточка не должна перезагружаться
 * целиком, а после отзыва плитки каналов обязаны стать серыми сразу.
 */
@Controller
@PreAuthorize("isAuthenticated()")
public class UiSubjectController {

    private static final int PAGE_SIZE = 20;

    private final UiSubjectViewService view;
    private final SubjectCardService cards;
    private final RevocationService revocation;
    private final UiSearchCriteria searchCriteria;

    public UiSubjectController(
            UiSubjectViewService view,
            SubjectCardService cards,
            RevocationService revocation,
            UiSearchCriteria searchCriteria) {
        this.view = view;
        this.cards = cards;
        this.revocation = revocation;
        this.searchCriteria = searchCriteria;
    }

    /**
     * Приём поискового запроса (UI-3).
     *
     * <p>Форма отправляется методом POST, а не GET: телефон, email и ФИО не должны попадать в адресную
     * строку, историю браузера, заголовок Referer и журналы прокси (UI-0.10). Значение остаётся в сессии,
     * наружу уходит только идентификатор запроса.
     */
    @PostMapping("/ui/subjects/search")
    public String submitSearch(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "20") int size,
            HttpSession session) {
        if (query == null || query.isBlank()) {
            return "redirect:/ui/subjects";
        }
        UUID searchId = searchCriteria.remember(session, query.trim());
        return "redirect:/ui/subjects?searchId=" + searchId + "&size=" + size;
    }

    @GetMapping("/ui/subjects")
    public String search(
            @RequestParam(required = false) UUID searchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpSession session,
            Model model) {
        String query = searchCriteria.recall(session, searchId);
        model.addAttribute("query", query);
        model.addAttribute("searchId", searchId);
        model.addAttribute("size", size);
        if (searchId != null && query == null) {
            // Сессия истекла или ссылку открыли в другом сеансе: показываем пустую форму, а не 500.
            model.addAttribute("searchHint", "Запрос устарел — введите его заново.");
        }
        if (query != null && !query.isBlank()) {
            try {
                Page<UiSubjectViewService.SubjectRow> results =
                        view.search(query, PageRequest.of(page, size < 1 ? PAGE_SIZE : size));
                model.addAttribute("results", results);
            } catch (ApiException e) {
                // Подсказка о формате запроса — часть экрана, а не ошибка сервера (UI-3).
                model.addAttribute("searchHint", e.getMessage());
            }
        }
        return "ui/subjects/search";
    }

    @GetMapping("/ui/subjects/{id}")
    public String card(@PathVariable UUID id, Model model) {
        model.addAttribute("card", view.card(id));
        return "ui/subjects/card";
    }

    /** Вкладки карточки (UI-4): грузятся фрагментом, чтобы страница не перезагружалась. */
    @GetMapping("/ui/subjects/{id}/tab/{tab}")
    public String tab(
            @PathVariable UUID id,
            @PathVariable String tab,
            @RequestParam(defaultValue = "false") boolean superseded,
            Model model) {
        model.addAttribute("card", view.card(id));
        model.addAttribute("showSuperseded", superseded);
        return switch (tab) {
            case "transfers" -> "ui/subjects/fragments :: transfers";
            case "history" -> historyFragment(id, model);
            default -> "ui/subjects/fragments :: consents";
        };
    }

    private String historyFragment(UUID id, Model model) {
        model.addAttribute("history", view.history(id));
        return "ui/subjects/fragments :: history";
    }

    /**
     * UI-0.10: раскрытие контакта доступно только ролям с правом на ПДн и оставляет запись в журнале
     * доступа — иначе «показать» превратилось бы в тихий обход маскирования.
     */
    @PostMapping("/ui/subjects/{id}/reveal")
    public String revealContact(@PathVariable UUID id, @RequestParam ContactType type, Model model) {
        model.addAttribute("contact", view.revealContact(id, type));
        return "ui/subjects/fragments :: contactValue";
    }

    /** UI-5: список согласий, которые погаснут вместе с выбранным. */
    @GetMapping("/ui/consents/{id}/revocation-dialog")
    public String revocationDialog(@PathVariable UUID id, Model model) {
        model.addAttribute("consent", view.consent(id));
        model.addAttribute("cascade", view.previewCascade(id));
        model.addAttribute("sources", RevocationSource.values());
        return "ui/subjects/fragments :: revokeDialog";
    }

    @PostMapping("/ui/consents/{id}/revoke")
    @PreAuthorize("hasAnyRole('MANAGER','DPO','ADMIN')")
    public String revoke(
            @PathVariable UUID id,
            @RequestParam String reason,
            @RequestParam RevocationSource revocationSource,
            @RequestParam String caseNumber,
            @RequestParam(required = false) String documentRef,
            @RequestParam(defaultValue = "false") boolean allAdvertising,
            Model model) {
        Map<String, Object> evidence =
                documentRef == null || documentRef.isBlank() ? Map.of() : Map.of("documentRef", documentRef);

        UUID subjectId = view.consent(id).consent().getSubjectId();
        List<RevocationService.RevocationResult> results = allAdvertising
                ? revocation.revokeAllAdvertising(subjectId, reason, revocationSource, caseNumber)
                : List.of(revocation.revoke(id, reason, revocationSource, caseNumber, evidence));

        model.addAttribute("card", view.card(subjectId));
        model.addAttribute("revocationMessage", view.revocationMessage(results));
        return "ui/subjects/fragments :: cardBody";
    }

    /** Плитки каналов обновляются отдельно: после отзыва они обязаны позеленеть или посереть сразу (UI-5). */
    @GetMapping("/ui/subjects/{id}/channels")
    public String channels(@PathVariable UUID id, Model model) {
        model.addAttribute("card", view.card(id));
        return "ui/subjects/fragments :: channels";
    }

    /** Порядок плиток на карточке фиксирован макетом UI-4. */
    public static List<CommunicationChannel> channelOrder() {
        return List.of(
                CommunicationChannel.PHONE_CALL,
                CommunicationChannel.SMS,
                CommunicationChannel.EMAIL,
                CommunicationChannel.PUSH,
                CommunicationChannel.MESSENGER,
                CommunicationChannel.POSTAL_MAIL);
    }

    /** Для шаблона: полное имя без вывода в заголовок окна (UI-0.10). */
    public static String fullNameOf(Subject subject) {
        return subject.getFullName();
    }
}
