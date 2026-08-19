package ru.example.inconsensu.catalog.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.common.application.PdnCategoryService;
import ru.example.inconsensu.common.domain.ActorType;
import ru.example.inconsensu.common.domain.AuditEventType;
import ru.example.inconsensu.common.domain.ChannelDenyReason;
import ru.example.inconsensu.common.domain.CommunicationChannel;
import ru.example.inconsensu.common.domain.ConsentCategory;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ConsentStatus;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.FormStatus;
import ru.example.inconsensu.common.domain.PdnCategory;
import ru.example.inconsensu.common.domain.RevocationSource;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.common.domain.ThirdPartyRole;
import ru.example.inconsensu.common.error.ApiException;

/**
 * FR-11.4: все справочники одним эндпоинтом, с русскими названиями для интерфейса.
 *
 * <p>Собраны и перечисления Приложения D, и таблицы-справочники, чтобы UI не собирал выпадающие списки из
 * нескольких разных вызовов.
 */
@RestController
@RequestMapping("/api/v1/dictionaries")
@PreAuthorize("isAuthenticated()")
public class DictionaryController {

    /** Элемент справочника: код для машины, название для человека (NFR-8). */
    public record DictionaryEntry(String code, String nameRu, Map<String, Object> attributes) {

        static DictionaryEntry of(String code, String nameRu) {
            return new DictionaryEntry(code, nameRu, Map.of());
        }
    }

    private final PdnCategoryService pdnCategories;
    private final ConsentTypeService consentTypes;

    public DictionaryController(PdnCategoryService pdnCategories, ConsentTypeService consentTypes) {
        this.pdnCategories = pdnCategories;
        this.consentTypes = consentTypes;
    }

    @GetMapping
    public Map<String, List<DictionaryEntry>> all() {
        Map<String, List<DictionaryEntry>> dictionaries = new LinkedHashMap<>();
        for (String name : names()) {
            dictionaries.put(name, byName(name));
        }
        return dictionaries;
    }

    @GetMapping("/{name}")
    public List<DictionaryEntry> byName(@PathVariable String name) {
        return switch (name) {
            case "pdn-categories" -> pdnCategories.activeCategories().stream()
                    .map(DictionaryController::pdnCategoryEntry)
                    .toList();
            case "consent-types" -> consentTypes.activeTypes().stream()
                    .map(DictionaryController::consentTypeEntry)
                    .toList();
            case "channels" -> entries(CommunicationChannel.values());
            case "consent-categories" -> entries(ConsentCategory.values());
            case "consent-sources" -> entries(ConsentSource.values());
            case "consent-statuses" -> entries(ConsentStatus.values());
            case "form-statuses" -> entries(FormStatus.values());
            case "signature-types" -> entries(SignatureType.values());
            case "revocation-sources" -> entries(RevocationSource.values());
            case "third-party-roles" -> entries(ThirdPartyRole.values());
            case "contact-types" -> entries(ContactType.values());
            case "actor-types" -> entries(ActorType.values());
            case "audit-event-types" -> entries(AuditEventType.values());
            case "channel-deny-reasons" -> entries(ChannelDenyReason.values());
            case "roles" -> entries(RoleCode.values());
            default -> throw ApiException.notFound("Справочник не найден: " + name);
        };
    }

    private static List<String> names() {
        return List.of(
                "pdn-categories",
                "consent-types",
                "channels",
                "consent-categories",
                "consent-sources",
                "consent-statuses",
                "form-statuses",
                "signature-types",
                "revocation-sources",
                "third-party-roles",
                "contact-types",
                "actor-types",
                "audit-event-types",
                "channel-deny-reasons",
                "roles");
    }

    private static DictionaryEntry pdnCategoryEntry(PdnCategory category) {
        return new DictionaryEntry(
                category.getCode(),
                category.getNameRu(),
                Map.of("special", category.isSpecial(), "biometric", category.isBiometric()));
    }

    private static DictionaryEntry consentTypeEntry(ConsentType type) {
        return new DictionaryEntry(
                type.getCode(),
                type.getNameRu(),
                Map.of(
                        "category", type.getCategory().name(),
                        "requiresThirdParty", type.isRequiresThirdParty(),
                        "channels", type.getChannels().stream().map(Enum::name).toList()));
    }

    private static <E extends Enum<E>> List<DictionaryEntry> entries(E[] values) {
        return Arrays.stream(values)
                .map(value -> DictionaryEntry.of(value.name(), nameRu(value)))
                .toList();
    }

    private static String nameRu(Enum<?> value) {
        try {
            return (String) value.getClass().getMethod("nameRu").invoke(value);
        } catch (Exception e) {
            return value.name();
        }
    }
}
