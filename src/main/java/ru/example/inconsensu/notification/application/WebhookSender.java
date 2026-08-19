package ru.example.inconsensu.notification.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.notification.domain.WebhookDelivery;
import ru.example.inconsensu.notification.domain.WebhookSignature;
import ru.example.inconsensu.notification.domain.WebhookSubscription;

/**
 * HTTP-доставка одного события в одну подписку (FR-9.4, §7.9).
 *
 * <p>Компонент не решает, повторять ли доставку, и ничего не пишет в базу — только выполняет вызов и
 * возвращает его исход. Ошибка сети и ответ 5xx для вызывающего одинаковы: обе ситуации попадают в
 * расписание повторов, разница остаётся в журнале доставок.
 */
@Component
public class WebhookSender {

    public static final String EVENT_HEADER = "X-InConsensu-Event";
    public static final String DELIVERY_HEADER = "X-InConsensu-Delivery-Id";

    private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() {};

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String signatureHeader;

    public WebhookSender(
            RestTemplateBuilder builder, ObjectMapper objectMapper, InConsensuProperties properties, Clock clock) {
        InConsensuProperties.Webhook webhook = properties.notifications().webhook();
        this.restTemplate = builder.connectTimeout(webhook.connectTimeout())
                .readTimeout(webhook.readTimeout())
                .defaultHeader(HttpHeaders.USER_AGENT, "InConsensu-Webhook/1.0")
                .build();
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.signatureHeader = webhook.signatureHeader();
    }

    /** Выполняет вызов и возвращает готовую запись журнала — успешную или с ошибкой. */
    public WebhookDelivery send(
            WebhookSubscription subscription, UUID outboxEventId, String eventType, String body, int attempt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(EVENT_HEADER, eventType);
        headers.set(DELIVERY_HEADER, outboxEventId.toString());
        headers.set(signatureHeader, WebhookSignature.sign(subscription.getSecret(), body));
        customHeaders(subscription).forEach(headers::set);

        try {
            ResponseEntity<String> response =
                    restTemplate.postForEntity(subscription.getUrl(), new HttpEntity<>(body, headers), String.class);
            return delivery(
                    subscription,
                    outboxEventId,
                    attempt,
                    response.getStatusCode().value(),
                    null);
        } catch (RestClientResponseException e) {
            return delivery(
                    subscription,
                    outboxEventId,
                    attempt,
                    e.getStatusCode().value(),
                    "HTTP " + e.getStatusCode().value());
        } catch (RuntimeException e) {
            // В тексте исключения только адрес и причина сетевой ошибки; тела запроса в нём нет (NFR-3).
            return delivery(
                    subscription, outboxEventId, attempt, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public String signatureHeader() {
        return signatureHeader;
    }

    private WebhookDelivery delivery(
            WebhookSubscription subscription, UUID outboxEventId, int attempt, Integer code, String error) {
        return new WebhookDelivery(
                UUID.randomUUID(), subscription.getId(), outboxEventId, attempt, code, error, clock.instant());
    }

    private Map<String, String> customHeaders(WebhookSubscription subscription) {
        try {
            return objectMapper.readValue(subscription.getHeaders(), HEADERS_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
