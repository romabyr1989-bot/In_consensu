package ru.example.inconsensu.ui.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Названия и группы настроек оператора для экрана UI-16.
 *
 * <p>Экран показывал ключи как есть — «cus.status.expiring-days», — то есть технические коды вместо
 * русских подписей, что запрещает UI-0.4. Здесь тот же список, что и в миграциях, но с человеческими
 * названиями, пояснениями и порядком: §16 перечисляет настройки именно группами.
 */
public final class UiSettingsCatalog {

    /**
     * @param hint пояснение под полем: в каком виде вводится значение
     * @param readOnly значение показывается, но не правится: оно прочитано при запуске (ADR-0084)
     */
    public record SettingView(String key, String label, String hint, String value, boolean readOnly) {}

    /** @param settings настройки группы в порядке §16 */
    public record SettingGroup(String title, List<SettingView> settings) {}

    private record Meta(String group, String label, String hint, boolean readOnly) {
        Meta(String group, String label, String hint) {
            this(group, label, hint, false);
        }
    }

    private static final Map<String, Meta> META = new LinkedHashMap<>();

    static {
        META.put("operator.name", new Meta("Реквизиты оператора", "Наименование оператора", ""));
        META.put("operator.address", new Meta("Реквизиты оператора", "Адрес", ""));
        META.put("operator.inn", new Meta("Реквизиты оператора", "ИНН", ""));
        META.put("operator.ogrn", new Meta("Реквизиты оператора", "ОГРН", ""));
        META.put("dpo.name", new Meta("Ответственный за ПДн", "ФИО", ""));
        META.put("dpo.email", new Meta("Ответственный за ПДн", "Email", ""));
        META.put("dpo.phone", new Meta("Ответственный за ПДн", "Телефон", ""));
        // Таймзона намеренно не редактируется в интерфейсе: по ней считаются календарные сроки и расписание
        // задач, которые прочитаны при запуске. Правка «на ходу» сдвинула бы смысл уже вычисленных дат, а
        // строка в этом справочнике обещала настройку, которой не существовало (ADR-0084).
        META.put(
                "inconsensu.timezone",
                new Meta("Работа системы", "Таймзона оператора", "Задаётся при установке: INCONSENSU_TIMEZONE", true));
        META.put(
                "inconsensu.status.expiring-days",
                new Meta("Работа системы", "Порог «заканчивается через N дней»", "Число дней"));
        META.put(
                "inconsensu.approval.required-roles",
                new Meta("Работа системы", "Роли, обязательные для одобрения формы", "Коды ролей через запятую"));
        META.put(
                "inconsensu.revocation.cascade-enabled",
                new Meta("Работа системы", "Каскадный отзыв зависимых согласий", "true или false"));
        META.put(
                "inconsensu.notification.thresholds",
                new Meta("Уведомления", "Пороги уведомлений по умолчанию", "Дни через запятую, например 30,15,7,1"));
        META.put(
                "inconsensu.notification.digest-threshold",
                new Meta("Уведомления", "С какого числа писем собирается сводка", "Число уведомлений"));
        META.put(
                "inconsensu.export.ttl",
                new Meta("Выгрузки партнёрам", "Срок жизни ссылки", "Период ISO-8601, например PT24H"));
        META.put(
                "inconsensu.selfservice.auth-mode",
                new Meta("Самообслуживание клиента", "Режим аутентификации", "SERVICE_TOKEN или OTP"));
        META.put("branding.primary-color", new Meta("Брендирование", "Основной цвет", "HEX, например #0d6efd"));
        META.put("branding.logo-url", new Meta("Брендирование", "Ссылка на логотип", ""));
        META.put(
                "inconsensu.retention.enabled",
                new Meta("Хранение и удаление", "Автоматическая политика хранения", "true или false"));
        META.put(
                "inconsensu.retention.consents-after-revocation",
                new Meta("Хранение и удаление", "Хранить отозванные согласия", "Период ISO-8601, например P3Y"));
        META.put(
                "inconsensu.retention.audit-events",
                new Meta("Хранение и удаление", "Хранить события аудита", "Период ISO-8601, например P5Y"));
        META.put(
                "inconsensu.retention.partner-exports",
                new Meta("Хранение и удаление", "Хранить выгрузки партнёрам", "Период ISO-8601, например P30D"));
        META.put(
                "inconsensu.retention.notifications",
                new Meta("Хранение и удаление", "Хранить журнал уведомлений", "Период ISO-8601, например P1Y"));
    }

    private static final String OTHER_GROUP = "Прочие настройки";

    private UiSettingsCatalog() {}

    /** Настройки по группам §16; незнакомые ключи попадают в «Прочие» — экран не должен их прятать. */
    public static List<SettingGroup> groups(Map<String, String> settings) {
        Map<String, List<SettingView>> byGroup = new LinkedHashMap<>();
        META.forEach((key, meta) -> {
            if (settings.containsKey(key)) {
                byGroup.computeIfAbsent(meta.group(), group -> new java.util.ArrayList<>())
                        .add(new SettingView(key, meta.label(), meta.hint(), settings.get(key), meta.readOnly()));
            }
        });
        settings.forEach((key, value) -> {
            if (!META.containsKey(key)) {
                byGroup.computeIfAbsent(OTHER_GROUP, group -> new java.util.ArrayList<>())
                        .add(new SettingView(key, key, "", value, false));
            }
        });
        return byGroup.entrySet().stream()
                .map(entry -> new SettingGroup(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }
}
