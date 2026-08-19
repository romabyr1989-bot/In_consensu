package ru.example.inconsensu.iam.application;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.iam.infrastructure.AppUserRepository;

/**
 * FR-11.1: the first administrator is created from the environment while the user table is still empty.
 *
 * <p>Only while it is empty: once an operator exists, credentials in the environment must never be able to resurrect
 * or overwrite an account.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final UserService userService;
    private final InConsensuProperties properties;

    public AdminBootstrap(AppUserRepository users, UserService userService, InConsensuProperties properties) {
        this.users = users;
        this.userService = userService;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        String login = properties.bootstrap().adminLogin();
        String password = properties.bootstrap().adminPassword();
        if (login.isBlank() || password.isBlank()) {
            LOG.warn("Таблица пользователей пуста, а INCONSENSU_ADMIN_LOGIN / INCONSENSU_ADMIN_PASSWORD не заданы: "
                    + "войти в систему будет невозможно. Задайте переменные и перезапустите приложение (FR-11.1).");
            return;
        }
        userService.create(
                login, password, properties.bootstrap().adminFullName(), null, Set.of(RoleCode.ADMIN.name()));
        LOG.info("Создан первый администратор «{}» из переменных окружения (FR-11.1)", login);
    }
}
