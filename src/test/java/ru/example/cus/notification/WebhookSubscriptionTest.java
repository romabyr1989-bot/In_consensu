package ru.example.cus.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.example.cus.notification.domain.WebhookSubscription;

/** FR-9.4: пустой список типов означает «все события», иначе подписка ломается при появлении нового типа. */
class WebhookSubscriptionTest {

    private static WebhookSubscription subscription() {
        return new WebhookSubscription(UUID.randomUUID(), "CRM", "https://crm.example.ru/hooks/cus", "secret");
    }

    @Test
    void emptyTypeListAcceptsEverything() {
        WebhookSubscription subscription = subscription();
        assertThat(subscription.accepts("consent.revoked")).isTrue();
        assertThat(subscription.accepts("form.published")).isTrue();
    }

    @Test
    void narrowedSubscriptionAcceptsOnlyListedTypes() {
        WebhookSubscription subscription = subscription();
        subscription.update("CRM", subscription.getUrl(), Set.of("consent.revoked"), "{}", true);

        assertThat(subscription.accepts("consent.revoked")).isTrue();
        assertThat(subscription.accepts("consent.granted")).isFalse();
    }

    @Test
    void inactiveSubscriptionAcceptsNothing() {
        WebhookSubscription subscription = subscription();
        subscription.update("CRM", subscription.getUrl(), Set.of(), "{}", false);

        assertThat(subscription.accepts("consent.revoked")).isFalse();
    }

    @Test
    void secretRotationKeepsSubscriptionOtherwiseIntact() {
        WebhookSubscription subscription = subscription();
        subscription.rotateSecret("new-secret");

        assertThat(subscription.getSecret()).isEqualTo("new-secret");
        assertThat(subscription.getUrl()).isEqualTo("https://crm.example.ru/hooks/cus");
    }
}
