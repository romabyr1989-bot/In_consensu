package ru.example.inconsensu.iam.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Роли сотрудника из токена внешнего IdP (FR-11.1, профиль {@code oidc}).
 *
 * <p>Каждый IdP кладёт роли по-своему: Keycloak — в {@code realm_access.roles}, другие — в плоский
 * {@code roles} или {@code groups}. Поэтому путь к claim задаётся настройкой, а разбор пути живёт здесь —
 * в домене, где его можно проверить без поднятия контекста и без сети.
 */
public final class OidcRoles {

    /** Умолчание Keycloak: чаще всего встречается в контуре заказчика. */
    public static final String DEFAULT_CLAIM = "realm_access.roles";

    private OidcRoles() {}

    /**
     * Названия ролей по пути вида {@code realm_access.roles} или {@code roles}.
     *
     * <p>Пустой список — нормальный ответ: у токена может не быть ролей вовсе, и это означает «прав нет», а
     * не ошибку разбора. Ролью считается только строка: вложенные объекты по этому пути игнорируются, иначе
     * в авторитеты попал бы результат {@code toString()} чужой структуры.
     */
    public static List<String> of(Map<String, Object> claims, String claimPath) {
        Object current = claims;
        for (String segment : (claimPath == null || claimPath.isBlank() ? DEFAULT_CLAIM : claimPath).split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return List.of();
            }
            current = map.get(segment);
        }
        if (!(current instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> role.toUpperCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
    }
}
