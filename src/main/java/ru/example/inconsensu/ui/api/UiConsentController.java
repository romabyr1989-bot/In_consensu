package ru.example.inconsensu.ui.api;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import ru.example.inconsensu.registry.application.ConsentEvidenceService;

/** UI-4a: досье согласия — точный текст версии, доказательства и цепочка событий. */
@Controller
// FR-12.2: досье показывает доказательства и цепочку событий — роли те же, что и у API-аналога.
@PreAuthorize("hasAnyRole('AUDITOR','DPO','ADMIN','MANAGER')")
public class UiConsentController {

    private final ConsentEvidenceService evidence;
    private final ru.example.inconsensu.ui.application.UiSubjectViewService view;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public UiConsentController(
            ConsentEvidenceService evidence,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            ru.example.inconsensu.ui.application.UiSubjectViewService view) {
        this.evidence = evidence;
        this.view = view;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/ui/consents/{id}")
    public String dossier(
            @PathVariable UUID id,
            @RequestParam(name = "revoked", defaultValue = "false") boolean revoked,
            Model model) {
        var dossier = evidence.of(id);
        model.addAttribute("revoked", revoked);
        // UI-4a: «Сведения о согласии» — тип, субъект, источник, даты, статус. Тип и субъект хранились
        // идентификаторами, и в досье их просто не было.
        model.addAttribute("summary", view.dossierSummary(dossier));
        model.addAttribute("dossier", dossier);
        // UI-4a: поля доказательств показываются без чувствительных значений — телефон, OTP и IP
        // маскируются, как и в ответе API (NFR-3).
        model.addAttribute("evidenceFields", evidence.maskedEvidence(dossier.consent(), objectMapper));
        model.addAttribute(
                "signatureTypeRu", dossier.consent().getSignatureType().nameRu());
        return "ui/consents/dossier";
    }

    /**
     * Точный текст формы, по которой дано согласие (UI-4, FR-1.6).
     *
     * <p>Открывается модальным окном из строки согласия: сотруднику нужно видеть не только название типа,
     * но и документ, который подписал клиент, — с версией, датой публикации и контрольной суммой.
     */
    @GetMapping("/ui/consents/{id}/text")
    public String consentText(@PathVariable UUID id, Model model) {
        model.addAttribute("dossier", evidence.of(id));
        return "ui/consents/fragments :: consentText";
    }
}
