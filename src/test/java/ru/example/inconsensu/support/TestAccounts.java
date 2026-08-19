package ru.example.inconsensu.support;

import java.util.Set;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.iam.application.UserService;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.iam.infrastructure.TokenService;

/** Creates employees with the roles of Приложение E and mints bearer tokens for them. */
@TestComponent
public class TestAccounts {

    public static final String PASSWORD = "correct-horse-battery";

    private final UserService userService;
    private final TokenService tokenService;

    public TestAccounts(UserService userService, TokenService tokenService) {
        this.userService = userService;
        this.tokenService = tokenService;
    }

    @Transactional
    public AppUser create(String roleCode) {
        return create(roleCode, null);
    }

    /** Учётная запись с почтой: получатели уведомлений по ролям берутся именно из неё (FR-9.2). */
    @Transactional
    public AppUser create(String roleCode, String email) {
        String login =
                roleCode.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        return userService.create(login, PASSWORD, "Тестовый " + roleCode, email, Set.of(roleCode));
    }

    public HttpHeaders authorizationFor(String roleCode) {
        return bearer(tokenService.issueAccessToken(create(roleCode)));
    }

    public static HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
