package ru.example.cus.registry.api;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.example.cus.catalog.application.ConsentFormService;
import ru.example.cus.catalog.application.ConsentTypeService;
import ru.example.cus.catalog.domain.ConsentForm;
import ru.example.cus.catalog.domain.ConsentType;
import ru.example.cus.common.config.CusProperties;
import ru.example.cus.registry.application.ConsentQueryService;
import ru.example.cus.thirdparty.application.ThirdPartyService;

/**
 * Подставляет в ответ названия из справочников других модулей (§5: только через application-сервисы).
 *
 * <p>Названия кэшируются на время сборки одного ответа: карточка клиента с десятком согласий одного типа не
 * должна превращаться в десяток одинаковых запросов к справочнику.
 */
@Component
public class ConsentResponseAssembler {

    private final ConsentTypeService types;
    private final ThirdPartyService thirdParties;
    private final ConsentFormService forms;
    private final CusProperties properties;

    public ConsentResponseAssembler(
            ConsentTypeService types,
            ThirdPartyService thirdParties,
            ConsentFormService forms,
            CusProperties properties) {
        this.types = types;
        this.thirdParties = thirdParties;
        this.forms = forms;
        this.properties = properties;
    }

    public ConsentResponse toResponse(ConsentQueryService.ConsentView view) {
        return toResponses(List.of(view)).get(0);
    }

    public List<ConsentResponse> toResponses(List<ConsentQueryService.ConsentView> views) {
        ZoneId zone = properties.timezone();
        Map<UUID, ConsentType> typeCache = new HashMap<>();
        Map<UUID, String> thirdPartyCache = new HashMap<>();
        Map<UUID, ConsentForm> formCache = new HashMap<>();

        return views.stream()
                .map(view -> {
                    var consent = view.consent();
                    ConsentType type = typeCache.computeIfAbsent(consent.getConsentTypeId(), types::get);
                    String thirdPartyName = consent.getThirdPartyId() == null
                            ? null
                            : thirdPartyCache.computeIfAbsent(
                                    consent.getThirdPartyId(),
                                    id -> thirdParties.get(id).getName());
                    ConsentForm form = consent.getFormId() == null
                            ? null
                            : formCache.computeIfAbsent(consent.getFormId(), forms::get);

                    return ConsentResponse.of(
                            view,
                            zone,
                            type.getCode(),
                            type.getNameRu(),
                            thirdPartyName,
                            form == null ? null : form.getCode(),
                            form == null ? null : form.getVersionNumber());
                })
                .toList();
    }
}
