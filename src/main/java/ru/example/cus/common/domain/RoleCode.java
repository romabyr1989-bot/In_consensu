package ru.example.cus.common.domain;

/** Роли пользователей (Приложение E). */
public enum RoleCode {
    ADMIN("Администратор"),
    DPO("Ответственный за ПДн"),
    LAWYER("Юрист"),
    MANAGER("Менеджер"),
    MARKETING("Маркетинг"),
    INTEGRATION("Интеграция"),
    AUDITOR("Аудитор");

    private final String nameRu;

    RoleCode(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
