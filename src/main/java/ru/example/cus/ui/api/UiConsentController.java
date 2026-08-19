package ru.example.cus.ui.api;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import ru.example.cus.registry.application.ConsentEvidenceService;

/** UI-4a: досье согласия — точный текст версии, доказательства и цепочка событий. */
@Controller
@PreAuthorize("isAuthenticated()")
public class UiConsentController {

    private final ConsentEvidenceService evidence;

    public UiConsentController(ConsentEvidenceService evidence) {
        this.evidence = evidence;
    }

    @GetMapping("/ui/consents/{id}")
    public String dossier(@PathVariable UUID id, Model model) {
        model.addAttribute("dossier", evidence.of(id));
        return "ui/consents/dossier";
    }
}
