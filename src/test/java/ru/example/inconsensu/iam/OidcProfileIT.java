package ru.example.inconsensu.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import ru.example.inconsensu.support.AbstractIntegrationTest;

/**
 * FR-11.1: профиль {@code oidc} действительно поднимает Resource Server внешнего IdP.
 *
 * <p>Профиль существовал заготовкой: файл настроек содержал закомментированные строки, флаг ни к чему не
 * привязывался, а страница входа предлагала кнопку на несуществующий адрес. Тест поднимает приложение с
 * профилем и проверяет то, что отличает рабочий профиль от заготовки: декодер построен по ключам IdP, роли
 * читаются из его claim, собственный вход по паролю закрыт.
 *
 * <p>Сети здесь нет намеренно: адрес набора ключей заведомо недоступен, а декодер по нему строится без
 * обращения к IdP — это и позволяет приложению стартовать, когда IdP ещё не поднят.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "inconsensu.iam.oidc.jwk-set-uri=http://127.0.0.1:1/realms/inconsensu/protocol/openid-connect/certs",
            "inconsensu.iam.oidc.issuer-uri=http://127.0.0.1:1/realms/inconsensu",
            "inconsensu.iam.oidc.roles-claim=realm_access.roles"
        })
@ActiveProfiles({"test", "oidc"})
class OidcProfileIT extends AbstractIntegrationTest {

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private Converter<Jwt, Collection<GrantedAuthority>> rolesConverter;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void decoder_comes_from_the_identity_provider() {
        assertThat(decoder)
                .as("в профиле oidc подпись обязан проверять ключ IdP, а не собственный секрет")
                .isInstanceOf(org.springframework.security.oauth2.jwt.NimbusJwtDecoder.class);
    }

    @Test
    void roles_of_the_identity_provider_become_authorities() {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("employee")
                .claim("realm_access", java.util.Map.of("roles", java.util.List.of("dpo")))
                .build();

        assertThat(rolesConverter.convert(token))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_DPO");
    }

    @Test
    void built_in_password_login_is_closed() {
        assertThat(restTemplate
                        .postForEntity(
                                "/api/v1/auth/login",
                                java.util.Map.of("login", "admin", "password", "любой"),
                                String.class)
                        .getStatusCode()
                        .value())
                .as("вход по паролю в профиле oidc обязан быть закрыт")
                .isIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }
}
