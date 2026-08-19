package ru.example.inconsensu.common.domain;

/** Категории типов согласий (Приложение D). */
public enum ConsentCategory {
    PROCESSING("Обработка ПДн"),
    ADVERTISING("Реклама"),
    TRANSFER("Передача третьим лицам"),
    DISTRIBUTION("Распространение"),
    OTHER("Прочее");

    private final String nameRu;

    ConsentCategory(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
