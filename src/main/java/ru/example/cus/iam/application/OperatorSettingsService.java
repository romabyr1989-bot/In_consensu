package ru.example.cus.iam.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.audit.application.AuditService;
import ru.example.cus.common.domain.AuditEventType;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.common.error.ErrorCode;
import ru.example.cus.common.security.CurrentUser;
import ru.example.cus.iam.domain.OperatorSetting;
import ru.example.cus.iam.infrastructure.OperatorSettingRepository;

/**
 * Настройки оператора (FR-11.3).
 *
 * <p>Ключи заводятся миграцией: произвольный набор из тела запроса создавал бы «настройки», которых никто не
 * читает, и прятал бы опечатки. Каждое изменение попадает в аудит (FR-10.1).
 */
@Service
public class OperatorSettingsService {

    public static final String AGGREGATE_TYPE = "operator_settings";
    public static final String AGGREGATE_ID = "singleton";

    private final OperatorSettingRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public OperatorSettingsService(OperatorSettingRepository repository, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, String> all() {
        Map<String, String> settings = new LinkedHashMap<>();
        repository.findAllByOrderByKeyAsc().forEach(setting -> settings.put(setting.getKey(), setting.getValue()));
        return settings;
    }

    @Transactional(readOnly = true)
    public String value(String key) {
        return repository.findById(key).map(OperatorSetting::getValue).orElse(null);
    }

    @Transactional
    public Map<String, String> update(Map<String, String> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Не переданы изменяемые настройки");
        }
        Map<String, Object> audited = new LinkedHashMap<>();
        changes.forEach((key, value) -> {
            OperatorSetting setting = repository
                    .findById(key)
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Неизвестная настройка: " + key));
            setting.change(value, clock.instant(), CurrentUser.login());
            repository.save(setting);
            audited.put(key, value);
        });
        auditService.record(AGGREGATE_TYPE, AGGREGATE_ID, AuditEventType.SETTINGS_CHANGED, audited);
        return all();
    }
}
