package ru.example.cus.common.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** Read-only view of who is performing the current call, used by audit and by the personal data access log. */
public final class CurrentUser {

    /** Actor recorded for background jobs and migrations, where no human is involved. */
    public static final String SYSTEM_LOGIN = "system";

    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_ROLES = "roles";

    private CurrentUser() {}

    /** Login of the caller, or {@link #SYSTEM_LOGIN} outside of an authenticated request. */
    public static String login() {
        return authentication()
                .map(Authentication::getName)
                .filter(name -> !name.isBlank())
                .orElse(SYSTEM_LOGIN);
    }

    public static Optional<UUID> id() {
        return authentication()
                .map(Authentication::getPrincipal)
                .filter(Jwt.class::isInstance)
                .map(Jwt.class::cast)
                .map(jwt -> jwt.getClaimAsString(CLAIM_USER_ID))
                .filter(value -> value != null && !value.isBlank())
                .map(UUID::fromString);
    }

    public static List<String> roles() {
        return authentication()
                .map(authentication -> authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .map(authority ->
                                authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)
                        .toList())
                .orElseGet(List::of);
    }

    private static Optional<Authentication> authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                ? Optional.of(authentication)
                : Optional.empty();
    }
}
