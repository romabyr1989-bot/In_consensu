package ru.example.inconsensu.common.domain;

/** Роль третьего лица (Приложение D). */
public enum ThirdPartyRole {
    PROCESSOR("Обработчик по поручению"),
    RECIPIENT("Самостоятельный получатель"),
    ECOSYSTEM("Компания экосистемы");

    private final String nameRu;

    ThirdPartyRole(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
