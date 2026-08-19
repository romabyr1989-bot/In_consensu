package ru.example.cus.common.domain;

/** Виды контактов субъекта (§6). */
public enum ContactType {
    PHONE("Телефон"),
    EMAIL("Электронная почта"),
    POSTAL_ADDRESS("Почтовый адрес");

    private final String nameRu;

    ContactType(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
