package ru.example.cus.notification.domain;

/** Канал доставки уведомления (FR-9.1). */
public enum NotificationChannel {
    EMAIL("письмо"),
    WEBHOOK("webhook");

    private final String nameRu;

    NotificationChannel(String nameRu) {
        this.nameRu = nameRu;
    }

    public String nameRu() {
        return nameRu;
    }
}
