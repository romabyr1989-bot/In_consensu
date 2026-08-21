package ru.example.inconsensu.iam.infrastructure;

import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.iam.domain.OidcRoles;

/**
 * Профиль {@code oidc}: токены выдаёт корпоративный IdP, а не сам сервис (FR-11.1).
 *
 * <p>Машинная цепочка §12 остаётся Resource Server'ом, меняется только источник доверия: подпись
 * проверяется ключами IdP, а роли берутся из его claim. Встроенный вход по паролю в этом профиле закрыт —
 * см. {@link SecurityConfig}: выдавать собственные токены, которые здесь же не принимаются, значит
 * оставить ловушку.
 *
 * <p>Интерфейс сотрудника §16 профиль не затрагивает: UI-0.3 требует form login с серверной сессией, и
 * роли для него берутся из справочника пользователей.
 */
@Configuration
@Profile("oidc")
public class OidcJwtConfig {

    private static final Logger LOG = LoggerFactory.getLogger(OidcJwtConfig.class);

    /**
     * Декодер токенов IdP.
     *
     * <p>Если задан адрес набора ключей, он предпочтителен: приложение стартует, не дожидаясь IdP, и
     * переживает его недоступность в момент запуска. Адрес издателя нужен, когда набор ключей заранее не
     * известен, — тогда метаданные читаются при старте.
     */
    @Bean
    public JwtDecoder jwtDecoder(InConsensuProperties properties) {
        InConsensuProperties.Oidc oidc = properties.iam().oidc();
        if (!oidc.jwkSetUri().isBlank()) {
            NimbusJwtDecoder decoder =
                    NimbusJwtDecoder.withJwkSetUri(oidc.jwkSetUri()).build();
            decoder.setJwtValidator(issuerValidator(oidc.issuerUri()));
            LOG.info("Профиль oidc: токены проверяются ключами {}", oidc.jwkSetUri());
            return decoder;
        }
        if (!oidc.issuerUri().isBlank()) {
            LOG.info("Профиль oidc: метаданные IdP читаются с {}", oidc.issuerUri());
            return JwtDecoders.fromIssuerLocation(oidc.issuerUri());
        }
        throw new IllegalStateException("Профиль oidc включён, но не задан ни inconsensu.iam.oidc.issuer-uri, "
                + "ни inconsensu.iam.oidc.jwk-set-uri: принимать токены не от кого");
    }

    private static OAuth2TokenValidator<Jwt> issuerValidator(String issuerUri) {
        return issuerUri.isBlank() ? JwtValidators.createDefault() : JwtValidators.createDefaultWithIssuer(issuerUri);
    }

    /** Роли из claim IdP: имя claim задаётся настройкой, потому что у каждого IdP оно своё. */
    @Bean
    public Converter<Jwt, Collection<GrantedAuthority>> jwtRolesConverter(InConsensuProperties properties) {
        String claim = properties.iam().oidc().rolesClaim();
        return jwt -> {
            List<String> roles = OidcRoles.of(jwt.getClaims(), claim);
            return roles.stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        };
    }
}
