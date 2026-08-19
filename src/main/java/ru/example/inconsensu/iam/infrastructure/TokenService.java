package ru.example.inconsensu.iam.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.common.security.CurrentUser;
import ru.example.inconsensu.iam.domain.AppUser;

/** Issues and validates the access and refresh tokens of FR-11.1. */
@Service
public class TokenService {

    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private final JwtEncoder encoder;
    private final NimbusJwtDecoder refreshTokenDecoder;
    private final InConsensuProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, SecretKey jwtSecretKey, InConsensuProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
        this.refreshTokenDecoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        this.refreshTokenDecoder.setJwtValidator(tokenTypeValidator(
                TOKEN_TYPE_REFRESH, properties.security().jwt().issuer()));
    }

    public String issueAccessToken(AppUser user) {
        return issue(user, TOKEN_TYPE_ACCESS, properties.security().jwt().accessTokenTtl());
    }

    public String issueRefreshToken(AppUser user) {
        return issue(user, TOKEN_TYPE_REFRESH, properties.security().jwt().refreshTokenTtl());
    }

    /** Returns the login carried by a valid refresh token. */
    public String loginFromRefreshToken(String token) {
        return refreshTokenDecoder.decode(token).getSubject();
    }

    public long accessTokenTtlSeconds() {
        return properties.security().jwt().accessTokenTtl().toSeconds();
    }

    static OAuth2TokenValidator<Jwt> accessTokenValidator(String issuer) {
        return tokenTypeValidator(TOKEN_TYPE_ACCESS, issuer);
    }

    private static OAuth2TokenValidator<Jwt> tokenTypeValidator(String expectedType, String issuer) {
        OAuth2TokenValidator<Jwt> standard =
                new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(), JwtValidators.createDefault());
        OAuth2TokenValidator<Jwt> typeValidator = jwt -> {
            if (!expectedType.equals(jwt.getClaimAsString(CLAIM_TOKEN_TYPE))) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Неверный тип токена", null));
            }
            // Compared as a raw claim on purpose: Jwt#getIssuer() insists on parsing the value as a URL, and the
            // issuer of a self-contained internal token is a plain name.
            if (!issuer.equals(jwt.getClaimAsString("iss"))) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Неверный издатель токена", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
        return new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(standard, typeValidator);
    }

    private String issue(AppUser user, String tokenType, java.time.Duration ttl) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.security().jwt().issuer())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(user.getLogin())
                .claim(CurrentUser.CLAIM_USER_ID, user.getId().toString())
                .claim(CurrentUser.CLAIM_ROLES, List.copyOf(user.getRoleCodes()))
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
