package ru.example.inconsensu.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.example.inconsensu.iam.domain.OidcRoles;

/** FR-11.1, профиль {@code oidc}: роли берутся из claim внешнего IdP, путь к нему задаётся настройкой. */
class OidcRolesTest {

    @Test
    void nested_claim_of_keycloak_is_read_by_its_path() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("DPO", "auditor")));

        assertThat(OidcRoles.of(claims, "realm_access.roles")).containsExactly("DPO", "AUDITOR");
    }

    @Test
    void flat_claim_is_read_too() {
        assertThat(OidcRoles.of(Map.of("roles", List.of("admin")), "roles")).containsExactly("ADMIN");
    }

    @Test
    void empty_claim_path_falls_back_to_the_default_of_keycloak() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("manager")));

        assertThat(OidcRoles.of(claims, "  ")).containsExactly("MANAGER");
    }

    /** Токен без ролей означает «прав нет», а не ошибку: пустой список — нормальный ответ. */
    @Test
    void missing_or_foreign_claim_gives_no_roles() {
        assertThat(OidcRoles.of(Map.of("sub", "employee"), "realm_access.roles"))
                .isEmpty();
        assertThat(OidcRoles.of(Map.of("realm_access", "не структура"), "realm_access.roles"))
                .isEmpty();
        assertThat(OidcRoles.of(Map.of("realm_access", Map.of("roles", "DPO")), "realm_access.roles"))
                .isEmpty();
    }

    /** Вложенные объекты по пути ролями не считаются: иначе в авторитеты попал бы их toString(). */
    @Test
    void only_strings_become_roles() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("DPO", Map.of("name", "chief"))));

        assertThat(OidcRoles.of(claims, "realm_access.roles")).containsExactly("DPO");
    }

    @Test
    void duplicates_and_blanks_are_dropped() {
        Map<String, Object> claims = Map.of("roles", List.of("DPO", "dpo", " ", "AUDITOR"));

        assertThat(OidcRoles.of(claims, "roles")).containsExactly("DPO", "AUDITOR");
    }
}
