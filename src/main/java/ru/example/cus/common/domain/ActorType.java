package ru.example.cus.common.domain;

/** Кто совершил действие (Приложение D). */
public enum ActorType {
    USER("Сотрудник"),
    SUBJECT("Клиент"),
    SYSTEM("Система"),
    INTEGRATION("Внешняя система");

    private final String nameRu;

    ActorType(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
