package ru.example.cus.notification.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.common.error.ErrorCode;

/**
 * Проверка адреса подписки (NFR-4).
 *
 * <p>Адрес подписки — это направление, куда ЦУС сам отправит запрос из внутреннего контура. Без списка
 * разрешённых хостов роль ADMIN превращается в возможность заставить сервис ходить в произвольный адрес,
 * включая внутренние (SSRF), поэтому в эксплуатации список обязателен.
 */
public final class WebhookUrlPolicy {

    private WebhookUrlPolicy() {}

    public static void check(String url, List<String> allowedHosts, boolean requireHttps) {
        if (url == null || url.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Укажите адрес подписки");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Адрес подписки записан неверно");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!List.of("http", "https").contains(scheme)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Адрес подписки должен начинаться с http:// или https://");
        }
        if (requireHttps && !"https".equals(scheme)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Разрешены только адреса https://");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "В адресе подписки не указан хост");
        }
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        boolean allowed = allowedHosts.stream()
                .map(candidate -> candidate.toLowerCase(Locale.ROOT).trim())
                .anyMatch(candidate -> normalized.equals(candidate)
                        // Запись вида «.example.ru» разрешает поддомены, но не сам суффикс в чужом домене.
                        || (candidate.startsWith(".") && normalized.endsWith(candidate)));
        if (!allowed) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Хост " + normalized + " не входит в список разрешённых для подписок");
        }
    }
}
