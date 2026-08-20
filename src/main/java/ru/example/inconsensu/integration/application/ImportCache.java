package ru.example.inconsensu.integration.application;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/**
 * Кэш справочников на один прогон импорта (FR-4.5, NFR-1).
 *
 * <p>Строки файла ссылаются на десяток типов и одну-две формы, но каждая строка перечитывала их заново:
 * тип по коду, все версии формы, саму форму ещё раз при проверке пригодности, третье лицо по ИНН. На
 * файле в сто тысяч строк это сотни тысяч лишних запросов.
 *
 * <p>Живёт ровно столько, сколько идёт задача, и создаётся заново на каждую: справочник могли изменить
 * между импортами, а общий кэш пришлось бы инвалидировать.
 */
public final class ImportCache {

    private final Map<String, ConsentType> types = new HashMap<>();
    private final Map<String, Optional<ConsentForm>> forms = new HashMap<>();
    private final Map<String, Optional<ThirdParty>> thirdParties = new HashMap<>();

    public ConsentType type(String code, Supplier<ConsentType> loader) {
        return types.computeIfAbsent(code, key -> loader.get());
    }

    /** Ключ включает дату согласия: без явного номера версия подбирается по ней (FR-2.3). */
    public Optional<ConsentForm> form(String code, Integer version, Instant grantedAt, Supplier<ConsentForm> loader) {
        String key = code + "|" + version + "|" + (version == null ? grantedAt : "");
        return forms.computeIfAbsent(key, ignored -> Optional.ofNullable(loader.get()));
    }

    public Optional<ThirdParty> thirdParty(String inn, Supplier<ThirdParty> loader) {
        return thirdParties.computeIfAbsent(inn, key -> Optional.ofNullable(loader.get()));
    }
}
