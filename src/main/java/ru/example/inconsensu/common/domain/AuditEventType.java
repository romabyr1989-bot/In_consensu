package ru.example.inconsensu.common.domain;

/** Типы событий журнала аудита (Приложение D, FR-10.1). */
public enum AuditEventType {
    CREATED("Создано"),
    UPDATED("Изменено"),
    DEACTIVATED("Деактивировано"),
    GRANTED("Согласие получено"),
    DECLINED("Отказано в пункте"),
    REVOKED("Согласие отозвано"),
    EXPIRED("Срок действия истёк"),
    EXPIRING("Скоро истекает"),
    SUPERSEDED("Заменено новым"),
    IMPORTED("Импортировано"),
    FORM_SUBMITTED("Форма отправлена на согласование"),
    FORM_APPROVED("Форма одобрена"),
    FORM_REJECTED("Форма возвращена на доработку"),
    FORM_PUBLISHED("Форма опубликована"),
    FORM_ARCHIVED("Форма отправлена в архив"),
    EXPORTED("Сформирована выгрузка"),
    SETTINGS_CHANGED("Изменены настройки"),
    LOGIN("Вход в систему");

    private final String nameRu;

    AuditEventType(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
