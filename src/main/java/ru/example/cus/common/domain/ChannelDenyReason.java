package ru.example.cus.common.domain;

/** Причины запрета канала коммуникации (Приложение D, FR-6.1). */
public enum ChannelDenyReason {
    NO_CONSENT("нет согласия"),
    REVOKED("согласие отозвано"),
    EXPIRED("срок действия истёк"),
    BASE_CONSENT_MISSING("нет базового согласия на обработку ПДн");

    private final String nameRu;

    ChannelDenyReason(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
