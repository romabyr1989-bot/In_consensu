package ru.example.inconsensu.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.notification.domain.WebhookUrlPolicy;

/** NFR-4: адрес подписки проверяется по списку разрешённых хостов — иначе ADMIN может отправить события куда угодно. */
class WebhookUrlPolicyTest {

    private static final List<String> ALLOWED = List.of("crm.example.ru", ".partner.example.ru");

    @Test
    void empty_allow_list_permits_any_host() {
        assertThatCode(() -> WebhookUrlPolicy.check("https://anything.example.org/hook", List.of(), false))
                .doesNotThrowAnyException();
    }

    @Test
    void listed_host_is_allowed() {
        assertThatCode(() -> WebhookUrlPolicy.check("https://crm.example.ru/hooks/cus", ALLOWED, false))
                .doesNotThrowAnyException();
    }

    @Test
    void subdomain_is_allowed_only_by_an_explicit_dot_prefix() {
        assertThatCode(() -> WebhookUrlPolicy.check("https://a.partner.example.ru/hook", ALLOWED, false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> WebhookUrlPolicy.check("https://sub.crm.example.ru/hook", ALLOWED, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("не входит в список");
    }

    @Test
    void lookalike_domain_is_rejected() {
        // «.partner.example.ru» не должен разрешать «evilpartner.example.ru.attacker.tld»
        assertThatThrownBy(() -> WebhookUrlPolicy.check("https://partner.example.ru.attacker.tld/hook", ALLOWED, false))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void non_http_scheme_is_rejected() {
        assertThatThrownBy(() -> WebhookUrlPolicy.check("ftp://crm.example.ru/hook", List.of(), false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("http://");
    }

    @Test
    void plain_http_is_rejected_when_https_is_required() {
        assertThatThrownBy(() -> WebhookUrlPolicy.check("http://crm.example.ru/hook", ALLOWED, true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("https://");
    }
}
