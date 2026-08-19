package ru.example.cus.iam.infrastructure;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import ru.example.cus.common.config.CusProperties;

/**
 * Signing key and JWT plumbing for the built-in authentication (FR-11.1).
 *
 * <p>The key comes from the environment (NFR-3). When it is absent the application still starts - with a random key
 * and a loud warning - so that a developer is never tempted to commit a default secret.
 */
@Configuration
public class JwtConfig {

    private static final Logger LOG = LoggerFactory.getLogger(JwtConfig.class);
    private static final int MINIMUM_SECRET_BYTES = 32;

    @Bean
    public SecretKey jwtSecretKey(CusProperties properties) {
        String configured = properties.security().jwt().secret();
        if (configured == null || configured.isBlank()) {
            LOG.warn("cus.security.jwt.secret не задан: сгенерирован временный ключ. "
                    + "Выданные токены станут недействительными при перезапуске и не будут приниматься "
                    + "другими экземплярами. Для эксплуатации задайте CUS_JWT_SECRET (NFR-3).");
            byte[] random = new byte[MINIMUM_SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            return new SecretKeySpec(random, "HmacSHA256");
        }
        byte[] secret = configured.getBytes(StandardCharsets.UTF_8);
        if (secret.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException("cus.security.jwt.secret короче " + MINIMUM_SECRET_BYTES
                    + " байт: HS256 требует ключ не меньше длины хеша");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    /**
     * Decoder used by the resource server: it accepts access tokens only.
     *
     * <p>Without the {@code typ} check a refresh token - which lives far longer - would work as a bearer token
     * everywhere, quietly defeating the short lifetime of the access token.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, CusProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(
                TokenService.accessTokenValidator(properties.security().jwt().issuer()));
        return decoder;
    }
}
