package ru.example.cus.ui.api;

import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.example.cus.catalog.application.ConsentFormService;
import ru.example.cus.common.domain.RoleCode;
import ru.example.cus.ui.application.UiBrandingService;

/**
 * Общие данные каркаса: брендирование, пользователь и счётчик форм, ждущих решения (UI-0.5, UI-0.12).
 *
 * <p>Собирается один раз на запрос, чтобы шаблонам не приходилось звать сервисы самим: §5 запрещает
 * логику в представлении, а счётчик в меню нужен на каждой странице.
 */
@ControllerAdvice(basePackages = "ru.example.cus.ui.api")
public class UiModelAdvice {

    private final UiBrandingService branding;
    private final ConsentFormService forms;

    public UiModelAdvice(UiBrandingService branding, ConsentFormService forms) {
        this.branding = branding;
        this.forms = forms;
    }

    @ModelAttribute
    public void common(Model model, Authentication authentication) {
        model.addAttribute("branding", branding.branding());
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
