package ru.example.inconsensu.notification.domain;

/** Состояние уведомления (§6). */
public enum NotificationStatus {
    PENDING("в очереди"),
    SENT("отправлено"),
    FAILED("не отправлено");

    private final String nameRu;

    NotificationStatus(String nameRu) {
        this.nameRu = nameRu;
    }

    public String nameRu() {
        return nameRu;
    }
}
