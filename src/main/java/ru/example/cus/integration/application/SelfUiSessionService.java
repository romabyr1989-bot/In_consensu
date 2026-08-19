package ru.example.cus.integration.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.common.config.CusProperties;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.common.security.CurrentUser;
import ru.example.cus.integration.domain.SelfUiSession;
import ru.example.cus.integration.infrastructure.SelfUiSessionRepository;
import ru.example.cus.registry.domain.Subject;

/**
 * Одноразовые ссылки на страницу самообслуживания (UI-18).
 *
 * <p>Личный кабинет получает ссылку, действующую пять минут, и открывает её во фрейме. Дальше работает
 * сессия страницы на пятнадцать минут: клиент успевает прочитать согласия и отозвать нужное, а
 * подсмотренная ссылка через несколько минут уже ничего не открывает.
 */
@Service
public class SelfUiSessionService {

    /** UI-18: сроки жизни ссылки и открытой по ней сессии; оба настраиваются. */
    public static final Duration DEFAULT_LINK_TTL = Duration.ofMinutes(5);

    public static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(15);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    /** @param url абсолютная ссылка, которую личный кабинет открывает клиенту */
    public record IssuedLink(String url, Instant expiresAt) {}

    private final SelfUiSessionRepository sessions;
    private final SelfServiceService selfService;
    private final CusProperties properties;
    private final Clock clock;

    public SelfUiSessionService(
            SelfUiSessionRepository sessions, SelfServiceService selfService, CusProperties properties, Clock clock) {
        this.sessions = sessions;
        this.selfService = selfService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Выдаёт одноразовую ссылку для клиента (FR-8.1, UI-18).
     *
     * @param externalIdFromRequest внешний идентификатор клиента; в режиме SUBJECT_JWT он берётся из токена,
     *     и переданное значение может быть только своим — проверку делает {@link SelfServiceService}
     */
    @Transactional
    public IssuedLink issue(String externalIdFromRequest) {
        Subject subject = selfService.currentSubject(externalIdFromRequest);
        sessions.deleteExpired(clock.instant());

        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        SelfUiSession session = new SelfUiSession(
                UUID.randomUUID(),
                hash(token),
                subject.getId(),
                CurrentUser.login(),
                clock.instant(),
                DEFAULT_LINK_TTL);
        sessions.save(session);

        String url = properties.notifications().baseUrl() + "/self/ui?token=" + token;
        return new IssuedLink(url, session.getLinkExpiresAt());
    }

    /** Открытие ссылки: она гасится сразу, дальше работает только сессия страницы. */
    @Transactional
    public SelfUiSession open(String token) {
        SelfUiSession session = sessions.findByTokenHash(hash(token))
                .orElseThrow(() -> ApiException.notFound("Ссылка недействительна или уже использована"));
        if (!session.isLinkUsable(clock.instant())) {
            throw ApiException.notFound("Ссылка недействительна или уже использована");
        }
        session.open(clock.instant(), DEFAULT_SESSION_TTL);
        return sessions.save(session);
    }

    @Transactional(readOnly = true)
    public SelfUiSession activeSession(UUID sessionId) {
        SelfUiSession session =
                sessions.findById(sessionId).orElseThrow(() -> ApiException.notFound("Сессия не найдена"));
        if (!session.isSessionActive(clock.instant())) {
            throw ApiException.notFound("Сессия истекла");
        }
        return session;
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** В базе живёт только хеш: по содержимому таблицы восстановить ссылку нельзя (NFR-3). */
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
