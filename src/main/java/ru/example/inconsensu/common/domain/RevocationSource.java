package ru.example.inconsensu.common.domain;

/** Источник обращения об отзыве (Приложение D). */
public enum RevocationSource {
    PERSONAL_ACCOUNT("Личный кабинет"),
    MOBILE_APP("Мобильное приложение"),
    WRITTEN_REQUEST("Письменное заявление"),
    CALL_CENTER("Звонок в колл-центр"),
    EMAIL_REQUEST("Обращение по email"),
    OFFICE("Обращение в офисе"),
    CASCADE("Каскадный отзыв");

    private final String nameRu;

    RevocationSource(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
