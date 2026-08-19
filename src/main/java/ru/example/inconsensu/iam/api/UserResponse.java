package ru.example.inconsensu.iam.api;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import ru.example.inconsensu.common.api.ApiTime;
import ru.example.inconsensu.iam.domain.AppUser;

/** Employee account as the administrator sees it (UI-16). The password hash never appears here. */
public record UserResponse(
        UUID id,
        String login,
        String fullName,
        String email,
        boolean active,
        Set<String> roles,
        boolean locked,
        OffsetDateTime lastLoginAt) {

    public static UserResponse of(AppUser user, ZoneId zone, java.time.Instant now) {
        return new UserResponse(
                user.getId(),
                user.getLogin(),
                user.getFullName(),
                user.getEmail(),
                user.isActive(),
                user.getRoleCodes(),
                user.isLocked(now),
                ApiTime.at(user.getLastLoginAt(), zone));
    }
}
