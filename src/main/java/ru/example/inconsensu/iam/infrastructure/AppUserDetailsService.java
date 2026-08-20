package ru.example.inconsensu.iam.infrastructure;

import java.time.Clock;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.common.security.AppUserPrincipal;
import ru.example.inconsensu.iam.domain.AppUser;

/**
 * Учётные данные сотрудника для входа в веб-интерфейс (UI-1, §16.2).
 *
 * <p>Справочник пользователей и ролей общий с API: интерфейс отличается только способом предъявления —
 * серверная сессия вместо JWT.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final Clock clock;

    public AppUserDetailsService(AppUserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String login) {
        AppUser user = users.findByLoginIgnoreCase(login)
                // Причина не уточняется: по UI-1 сообщение об ошибке одинаково для неверного логина и пароля.
                .orElseThrow(() -> new UsernameNotFoundException("Неверный логин или пароль"));
        // Идентификатор попадает в principal: без него журнал доступа к ПДн не знал бы, кто смотрел карточку.
        return new AppUserPrincipal(
                user.getId(),
                user.getLogin(),
                user.getPasswordHash(),
                authorities(user),
                user.isLocked(clock.instant()),
                user.isActive());
    }

    private static List<GrantedAuthority> authorities(AppUser user) {
        return user.getRoleCodes().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
