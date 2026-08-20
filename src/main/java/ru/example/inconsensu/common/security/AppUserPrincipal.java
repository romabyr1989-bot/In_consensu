package ru.example.inconsensu.common.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Пользователь веб-сессии вместе с его идентификатором (UI-0.3).
 *
 * <p>Стандартный {@code User} из Spring Security хранит только логин, поэтому при входе через форму
 * {@link CurrentUser#id()} возвращал пустое значение: журнал доступа к ПДн писал «кто» пустым (§7,
 * FR-10.4), а решения по формам сохранялись без автора. Тип объявлен в {@code common}, потому что его
 * читает {@link CurrentUser}, а зависеть от модуля iam общий код не вправе (§5).
 */
public final class AppUserPrincipal implements UserDetails {

    private final UUID id;
    private final String login;
    private final String passwordHash;
    private final List<GrantedAuthority> authorities;
    private final boolean locked;
    private final boolean active;

    public AppUserPrincipal(
            UUID id,
            String login,
            String passwordHash,
            Collection<? extends GrantedAuthority> authorities,
            boolean locked,
            boolean active) {
        this.id = id;
        this.login = login;
        this.passwordHash = passwordHash;
        this.authorities = List.copyOf(authorities);
        this.locked = locked;
        this.active = active;
    }

    public UUID id() {
        return id;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return login;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
