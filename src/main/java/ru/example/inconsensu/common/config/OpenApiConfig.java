package ru.example.inconsensu.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata (§4, §9).
 *
 * <p>The server list is pinned to a relative URL on purpose: springdoc would otherwise emit the concrete host and port,
 * which makes the generated document unstable and breaks the specification sync test of §11.
 */
@Configuration
public class OpenApiConfig {

    private static final String API_VERSION = "v1";

    /** §9: имя схемы безопасности в спецификации; на неё ссылается каждая операция. */
    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI cusOpenApi() {
        return new OpenAPI()
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes(
                                BEARER_SCHEME,
                                new io.swagger.v3.oas.models.security.SecurityScheme()
                                        .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Токен доступа из POST /api/v1/auth/login (FR-11.1)")))
                // §9: машинная цепочка работает по токену. Без этого блока в опубликованном контракте не было
                // сказано, чем аутентифицируется вызов, и клиент узнавал об этом только из 401.
                .addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList(BEARER_SCHEME))
                .info(new Info()
                        .title("In consensu — центр управления согласиями")
                        .version(API_VERSION)
                        .description("REST API центра управления согласиями субъектов персональных данных. "
                                + "Идентификаторы — на английском, пользовательские тексты — на русском (NFR-8).")
                        .contact(new Contact().name("DPO оператора"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url("/").description("Текущий хост")));
    }
}
