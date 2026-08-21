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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.common.domain.CommunicationChannel;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RevocationSource;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.registry.application.RevocationService;
import ru.example.inconsensu.registry.application.SubjectCardService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Subject;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.ui.application.UiSearchCriteria;
import ru.example.inconsensu.ui.application.UiSorting;
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
    private final ConsentTypeService types;
    private final ru.example.inconsensu.registry.application.SubjectCardPdfService cardPdf;
    private final ThirdPartyService thirdParties;

    public UiSubjectController(
            UiSubjectViewService view,
            SubjectCardService cards,
            RevocationService revocation,
            UiSearchCriteria searchCriteria,
            ConsentTypeService types,
            ThirdPartyService thirdParties,
            ru.example.inconsensu.registry.application.SubjectCardPdfService cardPdf) {
        this.view = view;
        this.cards = cards;
        this.revocation = revocation;
        this.searchCriteria = searchCriteria;
        this.types = types;
        this.thirdParties = thirdParties;
        this.cardPdf = cardPdf;
    }

    /**
     * Приём поискового запроса (UI-3).
     *
     * <p>Форма отправляется методом POST, а не GET: телефон, email и ФИО не должны попадать в адресную
     * строку, историю браузера, заголовок Referer и журналы прокси (UI-0.10). Значение остаётся в сессии,
     * наружу уходит только идентификатор запроса.
     */
    /** UI-0.8: колонки списка клиентов, по которым разрешена сортировка. */
    private static final java.util.Map<String, String> SUBJECT_SORT =
            java.util.Map.of("lastName", "lastName", "externalId", "externalId");

    /** UI-0.8: размеры страницы — 20, 50 или 100. */
    private static int normalizeSize(int size) {
        return size == 50 || size == 100 ? size : PAGE_SIZE;
    }

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
            @RequestParam(required = false) ConsentStatus status,
            @RequestParam(required = false) UUID consentTypeId,
            @RequestParam(required = false) UUID thirdPartyId,
            @RequestParam(required = false) ConsentSource source,
            @RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    java.time.LocalDate expiringBefore,
            @RequestParam(defaultValue = "false") boolean revokedOnly,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            HttpSession session,
            Model model) {
        String query = searchCriteria.recall(session, searchId);
        model.addAttribute("query", query);
        model.addAttribute("searchId", searchId);
        model.addAttribute("size", size);
        // UI-3: панель расширенных фильтров. Состояние держится в URL, значения возвращаются в форму.
        SubjectService.SubjectFilter filter = new SubjectService.SubjectFilter(
                status,
                consentTypeId,
                thirdPartyId,
                source,
                expiringBefore == null ? null : view.startOfNextDay(expiringBefore),
                revokedOnly);
        model.addAttribute("statuses", ConsentStatus.values());
        model.addAttribute("sources", ConsentSource.values());
        model.addAttribute("consentTypes", types.activeTypes());
        model.addAttribute(
                "thirdParties",
                thirdParties
                        .list(org.springframework.data.domain.Pageable.unpaged())
                        .getContent());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedConsentTypeId", consentTypeId);
        model.addAttribute("selectedThirdPartyId", thirdPartyId);
        model.addAttribute("selectedSource", source);
        model.addAttribute("selectedExpiringBefore", expiringBefore);
        model.addAttribute("revokedOnly", revokedOnly);
        model.addAttribute("filtersApplied", !filter.isEmpty());
        model.addAttribute("sort", sort);
        model.addAttribute("direction", direction);
        if (searchId != null && query == null) {
            // Сессия истекла или ссылку открыли в другом сеансе: показываем пустую форму, а не 500.
            model.addAttribute("searchHint", "Запрос устарел — введите его заново.");
        }
        // UI-2: список открывается и по одним фильтрам — тогда поискового запроса нет вовсе.
        if ((query != null && !query.isBlank()) || !filter.isEmpty()) {
            try {
                Page<UiSubjectViewService.SubjectRow> results = view.search(
                        query,
                        filter,
                        // Без явной сортировки Pageable уходит несортированным: тогда работает нативный
                        // запрос под индексом subject_full_name_prefix_idx, а порядок задаёт он сам.
                        PageRequest.of(
                                Math.max(page, 0),
                                normalizeSize(size),
                                UiSorting.of(
                                        sort,
                                        direction,
                                        SUBJECT_SORT,
                                        org.springframework.data.domain.Sort.unsorted())));
                model.addAttribute("results", results);
            } catch (ApiException e) {
                // Подсказка о формате запроса — часть экрана, а не ошибка сервера (UI-3).
                model.addAttribute("searchHint", e.getMessage());
            }
        }
        return "ui/subjects/search";
    }

    @GetMapping("/ui/subjects/{id}")
    public String card(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean superseded, Model model) {
        model.addAttribute("card", view.card(id, superseded));
        model.addAttribute("showSuperseded", superseded);
        return "ui/subjects/card";
    }

    /**
     * UI-4: карточка клиента в PDF из интерфейса.
     *
     * <p>Ссылка вела на `/api/v1/subjects/{id}/card.pdf`, где сессионная кука не принимается: кнопка
     * «Экспорт в PDF» всегда отвечала 401. Рендер тот же, права — как у карточки.
     */
    @GetMapping(
            value = "/ui/subjects/{id}/card.pdf",
            produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public org.springframework.http.ResponseEntity<byte[]> cardPdf(@PathVariable UUID id) {
        return org.springframework.http.ResponseEntity.ok()
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        // В имени файла только идентификатор: ФИО в имя файла и в URL не попадает (UI-0.10).
                        "attachment; filename=\"consent-card-" + id + ".pdf\"")
                .body(cardPdf.render(id));
    }

    /** Вкладки карточки (UI-4): грузятся фрагментом, чтобы страница не перезагружалась. */
    @GetMapping("/ui/subjects/{id}/tab/{tab}")
    public String tab(
            @PathVariable UUID id,
            @PathVariable String tab,
            @RequestParam(defaultValue = "false") boolean superseded,
            Model model) {
        model.addAttribute("card", view.card(id, superseded));
        model.addAttribute("showSuperseded", superseded);
        return switch (tab) {
            case "transfers" -> "ui/subjects/fragments :: transfers";
            case "history" -> historyFragment(id, null, null, null, model);
            default -> "ui/subjects/fragments :: consents";
        };
    }

    private String historyFragment(
            UUID id,
            ru.example.inconsensu.common.domain.AuditEventType eventType,
            java.time.LocalDate fromDay,
            java.time.LocalDate toDay,
            Model model) {
        model.addAttribute(
                "history", view.historyFeed(id, eventType, view.startOfDay(fromDay), view.startOfNextDay(toDay)));
        model.addAttribute("eventTypes", ru.example.inconsensu.common.domain.AuditEventType.values());
        model.addAttribute("selectedEventType", eventType);
        // UI-0.8: выбранный период возвращается в поля — иначе после «Показать» они пустеют и сотрудник
        // не видит, какой фильтр применён.
        model.addAttribute("selectedFrom", fromDay);
        model.addAttribute("selectedTo", toDay);
        model.addAttribute("subjectId", id);
        return "ui/subjects/fragments :: history";
    }

    /**
     * Лента событий клиента с фильтрами (UI-4).
     *
     * <p>Отдельная точка, потому что фильтры перезагружают только ленту: карточка по UI-4 не должна
     * перерисовываться целиком.
     */
    @GetMapping("/ui/subjects/{id}/history")
    public String history(
            @PathVariable UUID id,
            @RequestParam(required = false) ru.example.inconsensu.common.domain.AuditEventType eventType,
            @RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    java.time.LocalDate from,
            @RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    java.time.LocalDate to,
            Model model) {
        return historyFragment(id, eventType, from, to, model);
    }

    /** UI-4: проверка целостности цепочки событий клиента — функция аудита, значит и роли аудита (§16.2). */
    @PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN')")
    @PostMapping("/ui/subjects/{id}/history/verify")
    public String verifyHistory(@PathVariable UUID id, Model model) {
        model.addAttribute("report", view.verifySubjectHistory(id));
        return "ui/subjects/fragments :: integrityResult";
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

    /**
     * Источники обращения, которые выбирает сотрудник (UI-5).
     *
     * <p>Остальные значения перечисления проставляет система: личный кабинет и мобильное приложение —
     * самообслуживание клиента, каскад — сам движок отзыва (ADR-0080).
     */
    private static final List<RevocationSource> EMPLOYEE_SOURCES = List.of(
            RevocationSource.WRITTEN_REQUEST,
            RevocationSource.CALL_CENTER,
            RevocationSource.EMAIL_REQUEST,
            RevocationSource.OFFICE);

    /** UI-5: список согласий, которые погаснут вместе с выбранным. Диалог — начало отзыва, значит и права те же. */
    @PreAuthorize("hasAnyRole('MANAGER','DPO','ADMIN')")
    @GetMapping("/ui/consents/{id}/revocation-dialog")
    public String revocationDialog(
            @PathVariable UUID id, @RequestParam(defaultValue = "card") String returnTo, Model model) {
        model.addAttribute("consent", view.consent(id));
        model.addAttribute("consentTitle", view.consentTitle(id));
        model.addAttribute("cascade", view.previewCascade(id));
        model.addAttribute("sources", EMPLOYEE_SOURCES);
        model.addAttribute("returnTo", "dossier".equals(returnTo) ? "dossier" : "card");
        model.addAttribute("revocable", List.of());
        return "ui/subjects/fragments :: revokeDialog";
    }

    /**
     * UI-4: кнопка «Отозвать согласие» в шапке карточки.
     *
     * <p>Раньше кнопка вела на несуществующий маршрут и не делала ничего. Здесь согласие ещё не выбрано,
     * поэтому диалог открывается со списком отзываемых согласий клиента.
     */
    @PreAuthorize("hasAnyRole('MANAGER','DPO','ADMIN')")
    @GetMapping("/ui/subjects/{id}/revocation-dialog")
    public String subjectRevocationDialog(@PathVariable UUID id, Model model) {
        List<UiSubjectViewService.RevocableConsent> revocable = view.revocableConsents(id);
        if (revocable.size() == 1) {
            return revocationDialog(revocable.getFirst().id(), "card", model);
        }
        model.addAttribute("revocable", revocable);
        model.addAttribute("sources", EMPLOYEE_SOURCES);
        model.addAttribute("returnTo", "card");
        // Согласие ещё не выбрано, значит и каскад считать не по чему: он появится после выбора.
        model.addAttribute("cascade", List.of());
        model.addAttribute("consent", null);
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
            @RequestParam(defaultValue = "card") String returnTo,
            @RequestHeader(value = "HX-Request", required = false) String htmxRequest,
            jakarta.servlet.http.HttpServletResponse response,
            Model model) {
        Map<String, Object> evidence =
                documentRef == null || documentRef.isBlank() ? Map.of() : Map.of("documentRef", documentRef);

        UUID subjectId = view.consent(id).consent().getSubjectId();
        // Доказательства передаются и при массовом отзыве: письменное заявление требует ссылки на скан,
        // и без неё сочетание «все рекламные + письменное заявление» падало на проверке (FR-8.2).
        List<RevocationService.RevocationResult> results = allAdvertising
                ? revocation.revokeAllAdvertising(subjectId, reason, revocationSource, caseNumber, evidence)
                : List.of(revocation.revoke(id, reason, revocationSource, caseNumber, evidence));

        String message = view.revocationMessage(results);
        if (htmxRequest == null) {
            // Запрос без htmx (браузер без скриптов): фрагмент показывать некуда, поэтому обычный переход.
            return "redirect:"
                    + ("dossier".equals(returnTo)
                            ? "/ui/consents/" + id + "?revoked=true"
                            : "/ui/subjects/" + subjectId);
        }
        if ("dossier".equals(returnTo)) {
            // Диалог открыт из досье (UI-4a), а блока карточки там нет: раньше ответ уходил в
            // несуществующую цель #card-body, и окно просто зависало. Досье перечитывается целиком —
            // на нём меняются и статус, и доказательства, и лента событий.
            response.setHeader("HX-Redirect", "/ui/consents/" + id + "?revoked=true");
            return "ui/subjects/fragments :: revocationDone";
        }
        model.addAttribute("card", view.card(subjectId));
        model.addAttribute("showSuperseded", false);
        model.addAttribute("revocationMessage", message);
        return "ui/subjects/fragments :: cardBody";
    }

    /**
     * Заведение и правка клиента (UI-0.1, §9 `POST /subjects` — upsert по external_id).
     *
     * <p>Операция не интеграционная: по Приложению E ею пользуются MANAGER, DPO и ADMIN, а в интерфейсе
     * её не было — клиента можно было только найти. Ключ — внешний идентификатор: повторная отправка того
     * же идентификатора правит запись, а не создаёт вторую (FR-4.4).
     */
    @PostMapping("/ui/subjects")
    @PreAuthorize("hasAnyRole('MANAGER','DPO','ADMIN')")
    public String saveSubject(
            @RequestParam String externalId,
            @RequestParam String lastName,
            @RequestParam String firstName,
            @RequestParam(required = false) String middleName,
            @RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    java.time.LocalDate birthDate,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String email,
            RedirectAttributes redirect) {
        List<SubjectService.ContactForm> contacts = new java.util.ArrayList<>();
        if (phone != null && !phone.isBlank()) {
            contacts.add(new SubjectService.ContactForm(ContactType.PHONE, phone.trim(), true));
        }
        if (email != null && !email.isBlank()) {
            contacts.add(new SubjectService.ContactForm(ContactType.EMAIL, email.trim(), true));
        }
        try {
            var saved = view.saveSubject(new SubjectService.SubjectForm(
                    externalId.trim(),
                    lastName.trim(),
                    firstName.trim(),
                    middleName == null || middleName.isBlank() ? null : middleName.trim(),
                    birthDate,
                    contacts));
            redirect.addFlashAttribute("flashMessage", "Клиент сохранён");
            return "redirect:/ui/subjects/" + saved.getId();
        } catch (ApiException e) {
            ru.example.inconsensu.ui.application.UiFormErrors.report(redirect, e);
            return "redirect:/ui/subjects";
        }
    }

    /** UI-4: диалог из шапки карточки сначала спрашивает, какое согласие отзываем. */
    @PostMapping("/ui/consents/revoke")
    @PreAuthorize("hasAnyRole('MANAGER','DPO','ADMIN')")
    public String revokeSelected(
            @RequestParam UUID consentId,
            @RequestParam String reason,
            @RequestParam RevocationSource revocationSource,
            @RequestParam String caseNumber,
            @RequestParam(required = false) String documentRef,
            @RequestParam(defaultValue = "false") boolean allAdvertising,
            @RequestHeader(value = "HX-Request", required = false) String htmxRequest,
            jakarta.servlet.http.HttpServletResponse response,
            Model model) {
        return revoke(
                consentId,
                reason,
                revocationSource,
                caseNumber,
                documentRef,
                allAdvertising,
                "card",
                htmxRequest,
                response,
                model);
    }

    /** Плитки каналов обновляются отдельно: после отзыва они обязаны позеленеть или посереть сразу (UI-5). */
    @GetMapping("/ui/subjects/{id}/channels")
    public String channels(@PathVariable UUID id, Model model) {
        model.addAttribute("card", view.card(id));
        model.addAttribute("showSuperseded", false);
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
