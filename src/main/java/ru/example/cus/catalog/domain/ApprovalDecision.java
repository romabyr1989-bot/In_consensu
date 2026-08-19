package ru.example.cus.catalog.domain;

/** Решение согласующего по форме (FR-2.1, FR-2.2). */
public enum ApprovalDecision {
    APPROVED("одобрено"),
    REJECTED("возвращено на доработку");

    private final String nameRu;

    ApprovalDecision(String nameRu) {
        this.nameRu = nameRu;
    }

    public String nameRu() {
        return nameRu;
    }
}
