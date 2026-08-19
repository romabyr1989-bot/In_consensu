package ru.example.cus.registry.domain;

import java.util.Collection;
import java.util.Set;
import ru.example.cus.common.domain.RoleCode;

/**
 * Кому Приложение E открывает контакты клиента целиком (FR-5.1, NFR-3, UI-0.10).
 *
 * <p>Правило одно на API и на интерфейс: если бы экран решал сам, маскирование в списке и в ответе
 * эндпоинта могло бы разойтись — и роль без права на ПДн увидела бы телефон на странице.
 */
public final class ContactAccessPolicy {

    private static final Set<String> ROLES_WITH_FULL_CONTACTS =
            Set.of(RoleCode.MANAGER.name(), RoleCode.DPO.name(), RoleCode.ADMIN.name(), RoleCode.INTEGRATION.name());

    private ContactAccessPolicy() {}

    public static boolean seesFullContacts(Collection<String> roles) {
        return roles != null && roles.stream().anyMatch(ROLES_WITH_FULL_CONTACTS::contains);
    }
}
