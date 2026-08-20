package ru.example.inconsensu.ui.api;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.ui.application.UiBrandingService;
import ru.example.inconsensu.ui.application.UiFormats;

/**
 * Общие данные каркаса: брендирование, пользователь и счётчик форм, ждущих решения (UI-0.5, UI-0.12).
 *
 * <p>Собирается один раз на запрос, чтобы шаблонам не приходилось звать сервисы самим: §5 запрещает
 * логику в представлении, а счётчик в меню нужен на каждой странице.
 */
@ControllerAdvice(basePackages = "ru.example.inconsensu.ui.api")
public class UiModelAdvice {

    private final UiBrandingService branding;
    private final ConsentFormService forms;
    private final UiFormats formats;

    public UiModelAdvice(UiBrandingService branding, ConsentFormService forms, UiFormats formats) {
        this.branding = branding;
        this.forms = forms;
        this.formats = formats;
    }

    @ModelAttribute
    public void common(Model model, Authentication authentication, jakarta.servlet.http.HttpServletRequest request) {
        // UI-0.8: ссылка сортировки, пагинация и выбор размера страницы обязаны сохранять всё остальное
        // состояние экрана. Каждая из них выбрасывает только то, что задаёт сама: иначе переход на вторую
        // страницу сбрасывал бы сортировку, а смена сортировки — размер страницы и фильтры.
        model.addAttribute("sortQuery", queryWithout(request, SORT_LINK_PARAMS));
        model.addAttribute("pageQuery", queryWithout(request, PAGE_LINK_PARAMS));
        model.addAttribute("pageSizeParams", paramsWithout(request, PAGE_SIZE_FORM_PARAMS));
        model.addAttribute("branding", branding.branding());
        // UI-0.4: даты форматируются одним способом и в таймзоне оператора. #temporals.format в шаблоне
        // берёт таймзону JVM, из-за чего одно и то же время печаталось по-разному на сервере и у оператора.
        model.addAttribute("formats", formats);
        if (authentication == null || !authentication.isAuthenticated()) {
            return;
        }
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        model.addAttribute("currentUserLogin", authentication.getName());
        model.addAttribute("currentUserRoles", roles);
        model.addAttribute("currentUserRolesRu", rolesRu(roles));
        // UI-0.5: у пункта «Формы» счётчик виден только тем, кто действительно принимает решение.
        model.addAttribute(
                "awaitingFormsCount",
                roles.contains(RoleCode.LAWYER.name()) || roles.contains(RoleCode.DPO.name())
                        ? forms.awaitingDecision().size()
                        : 0);
    }

    /** Ссылка сортировки задаёт колонку, направление и возвращает на первую страницу. */
    private static final Set<String> SORT_LINK_PARAMS = Set.of("sort", "direction", "page");

    /** Ссылка пагинации задаёт только номер страницы. */
    private static final Set<String> PAGE_LINK_PARAMS = Set.of("page");

    /** Форма размера страницы задаёт размер и возвращает на первую страницу. */
    private static final Set<String> PAGE_SIZE_FORM_PARAMS = Set.of("size", "page");

    /** Параметры экрана без перечисленных: готовая строка вида «status=ACTIVE&amp;size=50&amp;». */
    private static String queryWithout(jakarta.servlet.http.HttpServletRequest request, Set<String> excluded) {
        StringBuilder query = new StringBuilder();
        paramsWithout(request, excluded)
                .forEach((name, value) -> query.append(java.net.URLEncoder.encode(name, StandardCharsets.UTF_8))
                        .append('=')
                        .append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8))
                        .append('&'));
        return query.toString();
    }

    private static java.util.Map<String, String> paramsWithout(
            jakarta.servlet.http.HttpServletRequest request, Set<String> excluded) {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (!excluded.contains(name) && values.length > 0 && !values[0].isBlank()) {
                params.put(name, values[0]);
            }
        });
        return params;
    }

    private static String rolesRu(Set<String> roles) {
        return roles.stream()
                .map(role -> {
                    try {
                        return RoleCode.valueOf(role).nameRu();
                    } catch (IllegalArgumentException e) {
                        return role;
                    }
                })
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
