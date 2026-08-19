package ru.example.cus.integration.domain;

/** Состояние задачи импорта (FR-4.5). */
public enum ImportJobStatus {
    PENDING("в очереди"),
    RUNNING("выполняется"),
    COMPLETED("завершена"),
    FAILED("завершилась ошибкой");

    private final String nameRu;

    ImportJobStatus(String nameRu) {
        this.nameRu = nameRu;
    }

    public String nameRu() {
        return nameRu;
    }
}
