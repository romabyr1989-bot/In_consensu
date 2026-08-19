package ru.example.inconsensu.notification.domain;

/** Повод для уведомления (FR-9.1, FR-9.2, §6). */
public enum NotificationTrigger {
    EXPIRING("заканчивается срок согласия"),
    EXPIRED("срок согласия истёк"),
    THIRD_PARTY_CONTRACT_EXPIRING("заканчивается договор с третьим лицом"),
    DELIVERY_FAILED("событие не доставлено");

    private final String nameRu;

    NotificationTrigger(String nameRu) {
        this.nameRu = nameRu;
    }

    public String nameRu() {
        return nameRu;
    }
}
