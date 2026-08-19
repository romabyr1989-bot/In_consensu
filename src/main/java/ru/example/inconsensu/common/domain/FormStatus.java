package ru.example.inconsensu.common.domain;

/** Статусы формы согласия (Приложение D, переходы — FR-2.1). */
public enum FormStatus {
    DRAFT("черновик"),
    ON_REVIEW("на согласовании"),
    APPROVED("одобрено"),
    PUBLISHED("опубликовано"),
    ARCHIVED("в архиве");

    private final String nameRu;

    FormStatus(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
