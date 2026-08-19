package ru.example.cus.common.domain;

/** Способы подписания согласия (Приложение D, состав доказательств — FR-4.2). */
public enum SignatureType {
    SIMPLE_ES_SMS("Простая ЭП по SMS"),
    SIMPLE_ES_LK("Простая ЭП в личном кабинете"),
    HANDWRITTEN("Собственноручная подпись"),
    UKEP("Усиленная квалифицированная ЭП"),
    IMPORTED_LEGACY("Импорт из базы клиентов");

    private final String nameRu;

    SignatureType(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
