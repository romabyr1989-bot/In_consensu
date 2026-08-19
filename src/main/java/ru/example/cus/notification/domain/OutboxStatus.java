package ru.example.cus.notification.domain;

/** Состояние записи outbox (§6). */
public enum OutboxStatus {
    PENDING("в очереди"),
    RETRY("повтор"),
    SENT("отправлено"),
    FAILED("не доставлено");

    private final String nameRu;

    OutboxStatus(String nameRu) {
        this.nameRu = nameRu;
    }

    public String nameRu() {
        return nameRu;
    }
}
