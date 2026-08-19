package ru.example.inconsensu.common.domain;

/** Точки контакта, где получено согласие (Приложение D). */
public enum ConsentSource {
    CLIENT_BASE_IMPORT("Импорт базы клиентов"),
    CONTRACT("Договор"),
    SUPPLEMENTARY_AGREEMENT("Дополнительное соглашение"),
    PERSONAL_ACCOUNT_REGISTRATION("Регистрация в личном кабинете"),
    WEBSITE_APPLICATION("Заявка на сайте"),
    LOYALTY_PROGRAM("Программа лояльности"),
    MOBILE_APP("Мобильное приложение"),
    CALL_CENTER("Колл-центр"),
    OFFICE("Офис"),
    OTHER("Иное");

    private final String nameRu;

    ConsentSource(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
