package ru.example.inconsensu.ui.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.notification.application.NotificationRuleService;
import ru.example.inconsensu.notification.application.NotificationService;
import ru.example.inconsensu.notification.domain.Notification;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationRule;
import ru.example.inconsensu.notification.domain.NotificationStatus;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/**
 * Модель экрана уведомлений (UI-13).
 *
 * <p>Таблица правил не показывала фильтры по типу согласия и третьему лицу, а журнал не имел фильтров
 * вовсе — при сотнях писем найти нужное было нечем. Значения готовятся здесь и приходят в шаблон уже
 * по-русски (§5, UI-0.4).
 */
@Service
public class UiNotificationViewService {

    /** @param filtersRu «все» или названия типа согласия и третьего лица, к которым привязано правило */
    public record RuleRow(
            UUID id,
            String name,
            String triggerRu,
            String thresholds,
            String filtersRu,
            String recipients,
            List<String> channelsRu,
            boolean active) {}

    /** @param canRetry повторная отправка предлагается только для неудавшихся (UI-13) */
    public record JournalRow(
            UUID id,
            String createdAt,
            String recipient,
            String subjectLine,
            String channelRu,
            String statusRu,
            String ruleName,
            String error,
            boolean canRetry) {}

    /** Просмотр текста письма (UI-13): что именно ушло получателю. */
    public record MessageView(
            UUID id,
            String recipient,
            String subjectLine,
            String body,
            String channelRu,
            String statusRu,
            String createdAt,
            String sentAt,
            String error) {}

    private final NotificationRuleService rules;
    private final NotificationService notifications;
    private final ConsentTypeService types;
    private final ThirdPartyService thirdParties;
    private final UiFormats formats;

    public UiNotificationViewService(
            NotificationRuleService rules,
            NotificationService notifications,
            ConsentTypeService types,
            ThirdPartyService thirdParties,
            UiFormats formats) {
        this.rules = rules;
        this.notifications = notifications;
        this.types = types;
        this.thirdParties = thirdParties;
        this.formats = formats;
    }

    @Transactional(readOnly = true)
    public List<RuleRow> rules(String sortField, boolean descending) {
        List<RuleRow> rows = rules();
        java.util.Comparator<RuleRow> comparator =
                switch (sortField == null ? "" : sortField) {
                    case "name" -> java.util.Comparator.comparing(RuleRow::name);
                    case "trigger" -> java.util.Comparator.comparing(RuleRow::triggerRu);
                    case "active" -> java.util.Comparator.comparing(RuleRow::active);
                    default -> null;
                };
        if (comparator == null) {
            return rows;
        }
        return rows.stream()
                .sorted(descending ? comparator.reversed() : comparator)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RuleRow> rules() {
        Map<UUID, String> typeNames = types.allTypes().stream()
                .collect(Collectors.toMap(ConsentType::getId, ConsentType::getNameRu, (first, second) -> first));
        Map<UUID, String> partyNames = thirdParties.list(Pageable.unpaged()).getContent().stream()
                .collect(Collectors.toMap(ThirdParty::getId, ThirdParty::getName, (first, second) -> first));
        return rules.list().stream()
                .map(rule -> new RuleRow(
                        rule.getId(),
                        rule.getName(),
                        rule.getTriggerType().nameRu(),
                        rule.getDaysBefore().stream().map(String::valueOf).collect(Collectors.joining(", ")),
                        filtersOf(rule, typeNames, partyNames),
                        recipientsOf(rule),
                        rule.getChannels().stream()
                                .map(NotificationChannel::nameRu)
                                .toList(),
                        rule.isActive()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<JournalRow> journal(
            NotificationStatus status,
            UUID ruleId,
            NotificationChannel channel,
            LocalDate from,
            LocalDate to,
            Pageable pageable) {
        Map<UUID, String> ruleNames =
                rules.list().stream().collect(Collectors.toMap(NotificationRule::getId, NotificationRule::getName));
        NotificationService.JournalFilter filter = new NotificationService.JournalFilter(
                status,
                ruleId,
                channel,
                from == null ? null : formats.startOfDay(from),
                // Верхняя граница — начало следующего дня: иначе «по 20.08» отсекало бы весь этот день.
                to == null ? null : formats.startOfDay(to.plusDays(1)));
        return notifications
                .list(filter, pageable)
                .map(notification -> new JournalRow(
                        notification.getId(),
                        formats.dateTime(notification.getCreatedAt()),
                        notification.getRecipient(),
                        notification.getSubjectLine(),
                        notification.getChannel().nameRu(),
                        notification.getStatus().nameRu(),
                        ruleNames.getOrDefault(notification.getRuleId(), ""),
                        notification.getLastError(),
                        notification.getStatus() == NotificationStatus.FAILED));
    }

    @Transactional(readOnly = true)
    public MessageView message(UUID id) {
        Notification notification = notifications.get(id);
        return new MessageView(
                notification.getId(),
                notification.getRecipient(),
                notification.getSubjectLine(),
                notification.getBody(),
                notification.getChannel().nameRu(),
                notification.getStatus().nameRu(),
                formats.dateTime(notification.getCreatedAt()),
                formats.dateTime(notification.getSentAt()),
                notification.getLastError());
    }

    private static String filtersOf(NotificationRule rule, Map<UUID, String> typeNames, Map<UUID, String> partyNames) {
        List<String> parts = new java.util.ArrayList<>();
        if (rule.getConsentTypeId() != null) {
            parts.add("тип: " + typeNames.getOrDefault(rule.getConsentTypeId(), "неизвестный"));
        }
        if (rule.getThirdPartyId() != null) {
            parts.add("третье лицо: " + partyNames.getOrDefault(rule.getThirdPartyId(), "неизвестное"));
        }
        return parts.isEmpty() ? "все" : String.join("; ", parts);
    }

    private static String recipientsOf(NotificationRule rule) {
        List<String> parts = new java.util.ArrayList<>(rule.getRecipientRoles().stream()
                .map(UiNotificationViewService::roleNameRu)
                .toList());
        parts.addAll(rule.getRecipientEmails());
        return parts.isEmpty() ? "не заданы" : String.join(", ", parts);
    }

    private static String roleNameRu(String role) {
        try {
            return RoleCode.valueOf(role).nameRu();
        } catch (IllegalArgumentException unknownRole) {
            return role;
        }
    }
}
