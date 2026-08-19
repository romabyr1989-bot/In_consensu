package ru.example.cus.ui.application;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.catalog.application.ConsentFormService;
import ru.example.cus.catalog.application.ConsentTypeService;
import ru.example.cus.catalog.application.FormWorkflowService;
import ru.example.cus.catalog.domain.ConsentForm;
import ru.example.cus.catalog.domain.ConsentType;
import ru.example.cus.catalog.domain.FormValidationResult;
import ru.example.cus.common.application.PdnCategoryService;
import ru.example.cus.common.domain.ConsentSource;
import ru.example.cus.common.domain.FormStatus;
import ru.example.cus.common.domain.PdnCategory;
import ru.example.cus.thirdparty.application.ThirdPartyService;
import ru.example.cus.thirdparty.domain.ThirdParty;

/** Модель экранов каталога (UI-6 … UI-10). */
@Service
public class UiCatalogViewService {

    /** @param counts счётчики согласий по типу — колонка таблицы UI-6 */
    public record TypeRow(ConsentType type, long active, long revoked) {}

    /** @param validation чек-лист реквизитов и нарушения для панели конструктора (UI-8) */
    public record FormView(
            ConsentForm form,
            String previewHtml,
            String checksum,
            FormValidationResult validation,
            List<ConsentForm> versions) {}

    private final ConsentTypeService types;
    private final ConsentFormService forms;
    private final FormWorkflowService workflow;
    private final ru.example.cus.catalog.application.CatalogStatsService stats;
    private final PdnCategoryService pdnCategories;
    private final ThirdPartyService thirdParties;

    public UiCatalogViewService(
            ConsentTypeService types,
            ConsentFormService forms,
            FormWorkflowService workflow,
            ru.example.cus.catalog.application.CatalogStatsService stats,
            PdnCategoryService pdnCategories,
            ThirdPartyService thirdParties) {
        this.types = types;
        this.forms = forms;
        this.workflow = workflow;
        this.stats = stats;
        this.pdnCategories = pdnCategories;
        this.thirdParties = thirdParties;
    }

    @Transactional(readOnly = true)
    public List<TypeRow> types() {
        var counts = stats.stats().byType();
        return types.list(Pageable.unpaged()).getContent().stream()
                .map(type -> {
                    var typeStats = counts.stream()
                            .filter(candidate -> candidate.code().equals(type.getCode()))
                            .findFirst();
                    return new TypeRow(
                            type,
                            typeStats
                                    .map(ru.example.cus.catalog.application.CatalogStatsService.TypeStats::active)
                                    .orElse(0L),
                            typeStats
                                    .map(ru.example.cus.catalog.application.CatalogStatsService.TypeStats::revoked)
                                    .orElse(0L));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<ConsentForm> forms(String status, Pageable pageable) {
        Page<ConsentForm> all = forms.list(pageable);
        if (status == null || status.isBlank()) {
            return all;
        }
        FormStatus filter = FormStatus.valueOf(status);
        List<ConsentForm> filtered = all.getContent().stream()
                .filter(form -> form.getStatus() == filter)
                .toList();
        return new org.springframework.data.domain.PageImpl<>(filtered, pageable, filtered.size());
    }

    @Transactional(readOnly = true)
    public List<ConsentForm> awaitingDecision() {
        return forms.awaitingDecision();
    }

    @Transactional(readOnly = true)
    public FormView form(UUID id) {
        ConsentForm form = forms.get(id);
        return new FormView(
                form, forms.preview(id), forms.checksumOf(form), forms.validate(id), forms.versionsOf(form.getCode()));
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
    public List<ru.example.cus.catalog.domain.TextDiff.Line> diff(UUID beforeId, UUID afterId) {
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
