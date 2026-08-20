package ru.example.inconsensu.common.security;

/**
 * Готовые выражения для {@code @PreAuthorize} (FR-11.2, Приложение E).
 *
 * <p>Выражение обязано быть константой времени компиляции, поэтому роли перечислены строками. Смысл в
 * том, чтобы разделение «сотрудник» и «служебная учётная запись» задавалось один раз: раньше на классах
 * стояло {@code isAuthenticated()}, и служебная роль INTEGRATION получала чтение каталога, форм,
 * справочника третьих лиц и карточки по внутреннему идентификатору — сверх матрицы Приложения E.
 */
public final class Authorities {

    /** Любой сотрудник: все роли, кроме служебной INTEGRATION (UI-0.3, Приложение E). */
    public static final String EMPLOYEE = "hasAnyRole('ADMIN','DPO','LAWYER','MANAGER','MARKETING','AUDITOR')";

    private Authorities() {}
}
