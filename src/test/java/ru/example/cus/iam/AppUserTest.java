package ru.example.cus.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.example.cus.iam.domain.AppUser;

/** FR-11.1: блокировка после N неудачных попыток и сброс счётчика после успешного входа. */
class AppUserTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");
    private static final Duration LOCK = Duration.ofMinutes(15);

    private final AppUser user = new AppUser(UUID.randomUUID(), "ivanova", "hash", "Иванова А. А.", "a@example.ru");

    @Test
    void fresh_account_is_active_and_unlocked() {
        assertThat(user.isActive()).isTrue();
        assertThat(user.isLocked(NOW)).isFalse();
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLogin()).isEqualTo("ivanova");
        assertThat(user.getFullName()).isEqualTo("Иванова А. А.");
        assertThat(user.getEmail()).isEqualTo("a@example.ru");
        assertThat(user.getRoles()).isEmpty();
        assertThat(user.getRoleCodes()).isEmpty();
        assertThat(user.getLastLoginAt()).isNull();
    }

    @Test
    void account_locks_only_after_the_configured_number_of_failures() {
        for (int attempt = 1; attempt < 5; attempt++) {
            user.registerFailedLogin(5, NOW, LOCK);
            assertThat(user.isLocked(NOW)).as("после %d неудачи", attempt).isFalse();
        }

        user.registerFailedLogin(5, NOW, LOCK);

        assertThat(user.isLocked(NOW)).isTrue();
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(LOCK));
    }

    @Test
    void lock_expires_by_itself() {
        user.registerFailedLogin(1, NOW, LOCK);

        assertThat(user.isLocked(NOW.plus(LOCK).minusSeconds(1))).isTrue();
        assertThat(user.isLocked(NOW.plus(LOCK).plusSeconds(1))).isFalse();
    }

    @Test
    void successful_login_clears_the_counter_and_the_lock() {
        user.registerFailedLogin(5, NOW, LOCK);
        user.registerFailedLogin(5, NOW, LOCK);

        user.registerSuccessfulLogin(NOW);

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void changing_the_password_unlocks_the_account() {
        user.registerFailedLogin(1, NOW, LOCK);

        user.changePassword("new-hash");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.isLocked(NOW)).isFalse();
        assertThat(user.getFailedLoginAttempts()).isZero();
    }

    @Test
    void profile_and_status_can_be_edited() {
        user.rename("Иванова Анна Андреевна", "anna@example.ru");
        user.setActive(false);

        assertThat(user.getFullName()).isEqualTo("Иванова Анна Андреевна");
        assertThat(user.getEmail()).isEqualTo("anna@example.ru");
        assertThat(user.isActive()).isFalse();
    }

    @Test
    void replacing_roles_drops_the_previous_set() {
        user.replaceRoles(Set.of());

        assertThat(user.getRoleCodes()).isEmpty();
    }
}
