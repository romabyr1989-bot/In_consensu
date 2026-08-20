package ru.example.inconsensu.ui.application;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.catalog.application.CatalogStatsService;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.catalog.application.FormWorkflowService;
import ru.example.inconsensu.catalog.domain.ApprovalDecision;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.catalog.domain.FormApproval;
import ru.example.inconsensu.catalog.domain.FormValidationResult;
import ru.example.inconsensu.common.application.PdnCategoryService;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.FormStatus;
import ru.example.inconsensu.common.domain.PdnCategory;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.application.UserService;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/** Модель экранов каталога (UI-6 … UI-10). */
@Service
public class UiCatalogViewService {

    /** @param expiringSoon согласия типа, срок которых истекает в ближайшие 30 дней (FR-3.4) */
    public record TypeRow(ConsentType type, long active, long expiringSoon, long revoked) {}

    /**
     * @param validation чек-лист реквизитов и нарушения для панели конструктора (UI-8)
     * @param issuedConsents сколько согласий выдано по этой версии — счётчик UI-10
     */
    public record FormView(
            ConsentForm form,
            String previewHtml,
            String checksum,
            FormValidationResult validation,
            List<ConsentForm> versions,
            long issuedConsents) {}

    /**
     * Пункт формы для чтения (UI-9, UI-10): все значения уже по-русски.
     *
     * <p>Собирается здесь, а не в шаблоне: на экранах печатались коды справочника ПДн и период ISO
     * («EMAIL», «P1Y»), что прямо запрещает UI-0.4.
     */
    public record FormItemRow(
            String typeRu,
            String text,
            String purposes,
            String pdnCategoriesRu,
            String thirdPartyRu,
            String validityRu,
            boolean mandatory) {}

    /**
     * Строка панели решений UI-9: «Юрист — одобрено, Иванова А. А., 15.08.2026 11:20».
     *
     * @param actor имя согласующего; логин, если учётной записи уже нет
     */
    public record ApprovalRow(String roleRu, boolean approved, String actor, Instant decidedAt, String comment) {}

    private final ConsentTypeService types;
    private final ConsentFormService forms;
    private final FormWorkflowService workflow;
    private final CatalogStatsService stats;
    private final PdnCategoryService pdnCategories;
    private final ThirdPartyService thirdParties;
    private final UserService users;
    private final UiFormats formats;

    public UiCatalogViewService(
            ConsentTypeService types,
            ConsentFormService forms,
            FormWorkflowService workflow,
            CatalogStatsService stats,
            PdnCategoryService pdnCategories,
            ThirdPartyService thirdParties,
            UserService users,
            UiFormats formats) {
        this.types = types;
        this.forms = forms;
        this.workflow = workflow;
        this.stats = stats;
        this.pdnCategories = pdnCategories;
        this.thirdParties = thirdParties;
        this.users = users;
        this.formats = formats;
    }

    /** UI-6: фильтры по категории и активности. */
    @Transactional(readOnly = true)
    public List<TypeRow> types(ru.example.inconsensu.common.domain.ConsentCategory category, Boolean active) {
        var counts = stats.byType().stream()
                .collect(Collectors.toMap(CatalogStatsService.TypeStats::code, Function.identity()));
        return types.list(category, active, Pageable.unpaged()).getContent().stream()
                .map(type -> {
                    var typeStats = counts.get(type.getCode());
                    return new TypeRow(
                            type,
                            typeStats == null ? 0L : typeStats.active(),
                            typeStats == null ? 0L : typeStats.expiringSoon(),
                            typeStats == null ? 0L : typeStats.revoked());
                })
                .toList();
    }

    /** UI-11: счётчики согласий по третьим лицам для списка партнёров (FR-3.4). */
    @Transactional(readOnly = true)
    public Map<UUID, CatalogStatsService.ThirdPartyStats> consentCountsByThirdParty() {
        return stats.byThirdParty().stream()
                .collect(Collectors.toMap(CatalogStatsService.ThirdPartyStats::id, Function.identity()));
    }

    /**
     * UI-7: фильтры выполняются запросом — иначе пагинация и счётчик страниц врут.
     *
     * <p>Источник, тип согласия и третье лицо поддержаны в запросе давно (ADR-0051), но экран передавал
     * на их месте null: сотрудник не мог отфильтровать каталог форм по партнёру или типу.
     */
    @Transactional(readOnly = true)
    public Page<ConsentForm> forms(
            String status, ConsentSource source, String typeCode, UUID thirdPartyId, String text, Pageable pageable) {
        FormStatus filter = status == null || status.isBlank() ? null : FormStatus.valueOf(status);
        return forms.list(
                new ConsentFormService.FormFilter(filter, source, blankToNull(typeCode), thirdPartyId, text), pageable);
    }

    @Transactional(readOnly = true)
    public List<ConsentForm> awaitingDecision() {
        return forms.awaitingDecision();
    }

    @Transactional(readOnly = true)
    public FormView form(UUID id) {
        ConsentForm form = forms.get(id);
        return new FormView(
                form,
                forms.preview(id),
                forms.checksumOf(form),
                forms.validate(id),
                forms.versionsOf(form.getCode()),
                stats.consentsOfForm(id));
    }

    /** UI-9, UI-10: пункты формы с русскими названиями категорий, партнёра и срока. */
    @Transactional(readOnly = true)
    public List<FormItemRow> items(UUID formId) {
        Map<String, String> categoryNames = pdnCategories.activeCategories().stream()
                .collect(Collectors.toMap(PdnCategory::getCode, PdnCategory::getNameRu, (first, second) -> first));
        Map<UUID, String> partyNames = thirdParties.list(Pageable.unpaged()).getContent().stream()
                .collect(Collectors.toMap(ThirdParty::getId, ThirdParty::getName, (first, second) -> first));
        return forms.get(formId).getItems().stream()
                .map(item -> new FormItemRow(
                        item.getConsentType().getNameRu(),
                        item.getText(),
                        String.join(", ", item.getPurposes()),
                        item.getPdnCategories().stream()
                                .map(code -> categoryNames.getOrDefault(code, code))
                                .collect(Collectors.joining(", ")),
                        item.getThirdPartyId() == null
                                ? "не требуется"
                                : partyNames.getOrDefault(item.getThirdPartyId(), "неизвестный партнёр"),
                        formats.period(item.getValidity()),
                        item.isMandatory()))
                .toList();
    }

    /**
     * UI-9: по каждой обязательной роли — решение, кто его принял и когда.
     *
     * <p>Раньше панель показывала код роли и слово «одобрено»: согласующий не видел ни фамилии, ни даты,
     * то есть не мог понять, актуально ли решение и с кем говорить о правках.
     */
    @Transactional(readOnly = true)
    public List<ApprovalRow> approvals(UUID id) {
        Map<String, FormApproval> byRole = new LinkedHashMap<>();
        for (FormApproval approval : workflow.approvalsOfCurrentRound(id)) {
            if (approval.getDecision() == ApprovalDecision.APPROVED) {
                byRole.put(approval.getRoleRequired(), approval);
            }
        }
        return workflow.requiredRoles().stream()
                .map(role -> {
                    FormApproval approval = byRole.get(role);
                    return new ApprovalRow(
                            roleNameRu(role),
                            approval != null,
                            approval == null ? null : actorName(approval),
                            approval == null ? null : approval.getDecidedAt(),
                            approval == null ? null : approval.getComment());
                })
                .toList();
    }

    private String actorName(FormApproval approval) {
        return users.displayName(approval.getUserId())
                .filter(name -> !name.isBlank())
                .orElseGet(approval::getUserLogin);
    }

    private static String roleNameRu(String role) {
        try {
            return RoleCode.valueOf(role).nameRu();
        } catch (IllegalArgumentException unknownRole) {
            // Список обязательных ролей приходит из настроек оператора и может содержать чужой код.
            return role;
        }
    }

    /** Предыдущая версия формы: с ней сравнивается черновик на экране согласования (FR-3.2). */
    @Transactional(readOnly = true)
    public java.util.Optional<ConsentForm> previousVersion(UUID id) {
        ConsentForm form = forms.get(id);
        return forms.versionsOf(form.getCode()).stream()
                .filter(candidate -> candidate.getVersionNumber() < form.getVersionNumber())
                .reduce((first, second) -> second);
    }

    @Transactional(readOnly = true)
    public List<ru.example.inconsensu.catalog.domain.TextDiff.Line> diff(UUID beforeId, UUID afterId) {
        return forms.diff(beforeId, afterId);
    }

    @Transactional(readOnly = true)
    public Set<String> approvedRoles(UUID id) {
        return workflow.approvedRoles(forms.get(id));
    }

    @Transactional(readOnly = true)
    public List<PdnCategory> pdnCategories() {
        return pdnCategories.activeCategories();
    }

    @Transactional(readOnly = true)
    public List<ThirdParty> thirdParties() {
        return thirdParties.list(Pageable.unpaged()).getContent();
    }

    /**
     * Сохранение черновика конструктора целиком (UI-8, ADR-0021).
     *
     * <p>Пункты приходят индексированными полями формы (items[0].typeCode и т. д.): браузер отправляет плоский
     * набор параметров, а собирать из него список удобнее здесь, чем описывать отдельный DTO на каждую версию
     * конструктора.
     */
    @Transactional
    public ConsentForm saveDraft(UUID id, HttpServletRequest request) {
        List<ConsentFormService.ItemForm> items = new ArrayList<>();
        int index = 0;
        while (request.getParameter("items[" + index + "].typeCode") != null) {
            String prefix = "items[" + index + "].";
            String thirdPartyId = request.getParameter(prefix + "thirdPartyId");
            items.add(new ConsentFormService.ItemForm(
                    request.getParameter(prefix + "typeCode"),
                    request.getParameter(prefix + "text"),
                    splitLines(request.getParameter(prefix + "purposes")),
                    values(request, prefix + "categories"),
                    thirdPartyId == null || thirdPartyId.isBlank() ? null : UUID.fromString(thirdPartyId),
                    blankToNull(request.getParameter(prefix + "validity")),
                    request.getParameter(prefix + "mandatory") != null));
            index++;
        }

        Set<ConsentSource> sources = new LinkedHashSet<>();
        for (String source : values(request, "sourceChannels")) {
            sources.add(ConsentSource.valueOf(source));
        }

        return forms.editDraft(
                id,
                new ConsentFormService.FormDraft(
                        request.getParameter("title"),
                        request.getParameter("body"),
                        request.getParameter("processingActions"),
                        request.getParameter("revocationProcedure"),
                        sources,
                        items));
    }

    private static List<String> values(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values == null ? List.of() : List.of(values);
    }

    /** Цели вводятся построчно: так их проще редактировать, чем в одну строку через запятую. */
    private static List<String> splitLines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return value.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
