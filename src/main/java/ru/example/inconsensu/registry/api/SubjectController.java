package ru.example.inconsensu.registry.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.channels.domain.ChannelDecision;
import ru.example.inconsensu.channels.domain.ChannelSummaryComposer;
import ru.example.inconsensu.common.api.ApiTime;
import ru.example.inconsensu.common.api.ChannelView;
import ru.example.inconsensu.common.api.PageResponse;
import ru.example.inconsensu.common.api.TransferView;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.security.Authorities;
import ru.example.inconsensu.registry.application.ConsentQueryService;
import ru.example.inconsensu.registry.application.SubjectCardService;
import ru.example.inconsensu.registry.application.SubjectService;

/** §9: субъекты — поиск, карточка по идентификатору и upsert по external_id (FR-5.2, FR-4.4). */
@RestController
@RequestMapping("/api/v1/subjects")
// Приложение E: служебной роли INTEGRATION карточка доступна только по external_id, поэтому обращения
// по внутреннему идентификатору и поиск закрыты для неё отдельными проверками ниже.
@PreAuthorize("isAuthenticated()")
public class SubjectController {

    public record ContactRequest(@NotNull ContactType type, @NotBlank @Size(max = 512) String value, boolean primary) {}

    public record UpsertSubjectRequest(
            @NotBlank @Size(max = 128) String externalId,
            @NotBlank @Size(max = 128) String lastName,
            @NotBlank @Size(max = 128) String firstName,
            @Size(max = 128) String middleName,
            LocalDate birthDate,
            List<@Valid ContactRequest> contacts) {

        SubjectService.SubjectForm toForm() {
            return new SubjectService.SubjectForm(
                    externalId,
                    lastName,
                    firstName,
                    middleName,
                    birthDate,
                    contacts == null
                            ? List.of()
                            : contacts.stream()
                                    .map(contact -> new SubjectService.ContactForm(
                                            contact.type(), contact.value(), contact.primary()))
                                    .toList());
        }
    }

    private final SubjectService service;
    private final ConsentQueryService consents;
    private final SubjectCardService cards;
    private final ru.example.inconsensu.registry.application.SubjectCardPdfService cardPdf;
    private final ConsentResponseAssembler assembler;
    private final ru.example.inconsensu.common.config.InConsensuProperties properties;

    public SubjectController(
            SubjectService service,
            ConsentQueryService consents,
            SubjectCardService cards,
            ru.example.inconsensu.registry.application.SubjectCardPdfService cardPdf,
            ConsentResponseAssembler assembler,
            ru.example.inconsensu.common.config.InConsensuProperties properties) {
        this.service = service;
        this.consents = consents;
        this.cards = cards;
        this.cardPdf = cardPdf;
        this.assembler = assembler;
        this.properties = properties;
    }

    /**
     * Поиск субъектов (§9).
     *
     * <p>`query` — единое поле интерфейса UI-3 с автоопределением вида запроса. `phone`, `email` и
     * `externalId` названы в контракте §9 отдельно: машинный клиент знает, что именно у него на руках, и
     * не должен зависеть от эвристики. Заполненный явный признак имеет приоритет над `query`.
     */
    @PreAuthorize(Authorities.EMPLOYEE)
    @GetMapping
    public PageResponse<SubjectResponse> search(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "phone", required = false) String phone,
            @RequestParam(name = "email", required = false) String email,
            @RequestParam(name = "externalId", required = false) String externalId,
            @PageableDefault(size = 20) Pageable pageable) {
        boolean fullContacts = SubjectResponse.currentRoleSeesFullContacts();
        return PageResponse.of(
                service.searchBy(query, phone, email, externalId, pageable),
                subject -> SubjectResponse.of(subject, fullContacts));
    }

    @PreAuthorize(Authorities.EMPLOYEE)
    @GetMapping("/{id}")
    public SubjectResponse get(@PathVariable UUID id) {
        return SubjectResponse.of(service.get(id));
    }

    @GetMapping("/by-external-id/{externalId}")
    public SubjectResponse getByExternalId(@PathVariable String externalId) {
        return SubjectResponse.of(service.getByExternalId(externalId));
    }

    /** FR-5.1: сводная картина по клиенту — что действует, что отозвано, что скоро закончится. */
    @PreAuthorize(Authorities.EMPLOYEE)
    @GetMapping("/{id}/card")
    public SubjectCardResponse card(@PathVariable UUID id) {
        return toResponse(cards.cardOf(id));
    }

    @GetMapping("/by-external-id/{externalId}/card")
    public SubjectCardResponse cardByExternalId(@PathVariable String externalId) {
        return toResponse(cards.cardByExternalId(externalId));
    }

    /** FR-5.1: полная история согласий субъекта, включая заменённые и отозванные. */
    /**
     * UI-4, этап 8: карточка клиента в PDF. Контакты маскируются по роли, как и на экране (NFR-3).
     *
     * <p>Проверка роли обязательна и здесь: у соседних методов карточки она стояла, а у выгрузки в PDF её
     * не было — служебная роль без права на ПДн получала файл с карточкой клиента.
     */
    @PreAuthorize(Authorities.EMPLOYEE)
    @GetMapping(value = "/{id}/card.pdf", produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    public org.springframework.http.ResponseEntity<byte[]> cardPdf(@PathVariable UUID id) {
        byte[] pdf = cardPdf.render(id);
        return org.springframework.http.ResponseEntity.ok()
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        // В имени файла только идентификатор: ФИО в имя файла и в URL не попадает (UI-0.10).
                        "attachment; filename=\"consent-card-" + id + ".pdf\"")
                .body(pdf);
    }

    @PreAuthorize(Authorities.EMPLOYEE)
    @GetMapping("/{id}/history")
    public List<ConsentResponse> history(@PathVariable UUID id) {
        return assembler.toResponses(consents.historyOf(id));
    }

    /** Перевод собранной карточки в ответ API: сборка общая с интерфейсом (§16), формат — только здесь. */
    private SubjectCardResponse toResponse(SubjectCardService.SubjectCard card) {
        return new SubjectCardResponse(
                SubjectResponse.of(card.subject()),
                assembler.toResponses(card.consents()),
                card.channels().stream().map(this::toView).toList(),
                card.summaryRu(),
                card.transfers().stream()
                        .map(permission -> new TransferView(
                                new TransferView.ThirdPartyRef(
                                        permission.thirdPartyId(),
                                        permission.thirdPartyName(),
                                        permission.thirdPartyRole()),
                                permission.allowedCategories(),
                                ApiTime.at(permission.validUntil(), properties.timezone()),
                                permission.daysLeft(),
                                permission.basisConsentId(),
                                permission.contractExpired()))
                        .toList(),
                ApiTime.at(card.generatedAt(), properties.timezone()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('INTEGRATION','ADMIN','DPO')")
    public SubjectResponse upsert(@Valid @RequestBody UpsertSubjectRequest request) {
        return SubjectResponse.of(service.upsert(request.toForm()));
    }

    /**
     * Перевод решения по каналу в ответ API.
     *
     * <p>Отображение живёт в слое api, а не в домене: правило §7.6 не должно знать про формат ответа (§5).
     * Такой же метод есть в карточке клиента — небольшое повторение здесь дешевле, чем зависимость домена
     * от представления или взаимная зависимость модулей.
     */
    private ChannelView toView(ChannelDecision decision) {
        return new ChannelView(
                decision.channel().name(),
                decision.channel().nameRu(),
                decision.allowed(),
                decision.basis() == null
                        ? null
                        : new ChannelView.Basis(
                                decision.basis().consentId(),
                                decision.basis().typeCode(),
                                ApiTime.at(decision.basis().validUntil(), properties.timezone())),
                decision.reason() == null ? null : decision.reason().name(),
                ChannelSummaryComposer.reasonText(decision.reason()));
    }
}
