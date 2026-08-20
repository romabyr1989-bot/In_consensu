package ru.example.inconsensu.notification.domain;

/** Повод для уведомления (FR-9.1, FR-9.2, §6). */
public enum NotificationTrigger {
    EXPIRING("заканчивается срок согласия"),
    EXPIRED("срок согласия истёк"),
    // §6 перечисляет GRANTED и REVOKED в составе notification_rule.trigger. Без них нельзя было создать
    // правило на отзыв, а FR-8.5 требует извещать DPO о прекращении обработки.
    GRANTED("получено согласие"),
    REVOKED("согласие отозвано"),
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
