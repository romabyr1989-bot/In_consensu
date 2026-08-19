package ru.example.inconsensu.common.domain;

/** Статусы согласия (Приложение D, правило расчёта — FR-5.3). */
public enum ConsentStatus {
    ACTIVE("действует"),
    EXPIRING("заканчивается"),
    EXPIRED("истекло"),
    REVOKED("отозвано"),
    SUPERSEDED("заменено новым");

    private final String nameRu;

    ConsentStatus(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
