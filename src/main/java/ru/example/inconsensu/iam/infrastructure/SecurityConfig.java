package ru.example.inconsensu.iam.infrastructure;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.common.error.ProblemDetailWriter;
import ru.example.inconsensu.common.security.CurrentUser;

/**
 * Stateless JWT protection of the API (FR-11.1, FR-11.2, NFR-3).
 *
 * <p>Deny by default: everything that is not listed as public requires a token, and the per-method
 * {@code @PreAuthorize} of Приложение E decides what the token is allowed to do.
 *
 * <p>The chain is scoped to the machine facing paths. The web interface (§16) is served by
 * {@link ru.example.inconsensu.ui.infrastructure.UiSecurityConfig} with a server side session and CSRF (UI-0.3): a browser
 * must not carry a bearer token in JavaScript, and an API client must not be redirected to a login page.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ru.example.inconsensu.common.config.InConsensuProperties properties;
    private final ProblemDetailWriter problemDetailWriter;

    public SecurityConfig(
            ru.example.inconsensu.common.config.InConsensuProperties properties,
            ProblemDetailWriter problemDetailWriter) {
        this.properties = properties;
        this.problemDetailWriter = problemDetailWriter;
    }

    /**
     * NFR-3: список источников задаётся настройкой, а не кодом.
     *
     * <p>Пустой список по умолчанию означает «CORS выключен»: интерфейс и API живут на одном домене, а
     * доступ из чужого источника оператор включает осознанно. Раньше вызывался {@code withDefaults()},
     * и источник молча брался из аннотаций, которых в проекте нет, — настроить CORS было нельзя вовсе.
     */
    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        ru.example.inconsensu.common.config.InConsensuProperties.Cors cors =
                properties.security().cors();
        org.springframework.web.cors.UrlBasedCorsConfigurationSource source =
                new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        if (cors == null
                || cors.allowedOrigins() == null
                || cors.allowedOrigins().isEmpty()) {
            return source;
        }
        org.springframework.web.cors.CorsConfiguration configuration =
                new org.springframework.web.cors.CorsConfiguration();
        configuration.setAllowedOrigins(cors.allowedOrigins());
        configuration.setAllowedMethods(cors.allowedMethods());
        configuration.setAllowedHeaders(cors.allowedHeaders());
        configuration.setAllowCredentials(cors.allowCredentials());
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @org.springframework.core.annotation.Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, HandlerMappingIntrospector introspector)
            throws Exception {
        List<String> publicPaths = new ArrayList<>(List.of(
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/actuator/health",
                "/actuator/health/**",
                "/actuator/info"));
        if (properties.security().publicApiDocs()) {
            publicPaths.addAll(List.of(
                    "/v3/api-docs",
                    // springdoc serves the YAML form as a sibling path, not as a child of /v3/api-docs
                    "/v3/api-docs.yaml",
                    "/v3/api-docs/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**"));
        }
        if (properties.security().publicMetrics()) {
            publicPaths.add("/actuator/prometheus");
        }

        // Цепочка ограничена машинными путями: интерфейс §16 живёт на /ui/** и /self/ui/** с сессией и CSRF.
        http.securityMatcher(
                        "/api/**",
                        "/actuator/**",
                        "/v3/api-docs",
                        "/v3/api-docs.yaml",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .requestMatchers(publicPaths.toArray(String[]::new))
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                                .authenticationEntryPoint((request, response, exception) -> problemDetailWriter.write(
                                        response, ErrorCode.UNAUTHORIZED, request.getRequestURI()))
                                .accessDeniedHandler((request, response, exception) -> problemDetailWriter.write(
                                        response, ErrorCode.ACCESS_DENIED, request.getRequestURI())))
                .exceptionHandling(handling -> handling.authenticationEntryPoint((request, response, exception) ->
                                problemDetailWriter.write(response, ErrorCode.UNAUTHORIZED, request.getRequestURI()))
                        .accessDeniedHandler((request, response, exception) ->
                                problemDetailWriter.write(response, ErrorCode.ACCESS_DENIED, request.getRequestURI())))
                // The security chain never renders HTML, so a browser must not be offered a login popup either.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        return http.build();
    }

    /** Maps the {@code roles} claim onto {@code ROLE_*} authorities so that {@code hasRole('DPO')} works. */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authorities);
        return converter;
    }

    private static List<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(CurrentUser.CLAIM_ROLES);
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
