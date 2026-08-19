package ru.example.inconsensu.thirdparty.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Кому и какие данные субъекта можно передать (FR-7.2, FR-7.3).
 *
 * <p>Разрешённый состав — пересечение категорий, на которые дано согласие, и категорий, разрешённых договором
 * с третьим лицом: расширить согласие договором нельзя, как и наоборот.
 *
 * <p>Срок — минимум из срока согласия и срока договора. Договор, истекший раньше согласия, закрывает передачу,
 * даже если клиент не возражает: у оператора нет правового основания передавать данные (FR-7.1).
 */
public final class TransferEvaluator {

    private TransferEvaluator() {}

    /** Разрешение на передачу конкретному третьему лицу. */
    public record TransferPermission(
            UUID thirdPartyId,
            String thirdPartyName,
            String thirdPartyRole,
            List<String> allowedCategories,
            Instant validUntil,
            Long daysLeft,
            UUID basisConsentId,
            boolean contractExpired) {}

    /**
     * Разрешённые передачи субъекта (FR-7.2).
     *
     * @param baseConsentUsable живо ли базовое согласие на обработку ПДн. Без него разрешений нет вовсе:
     *     §8.3 п.3 распространяет требование живого PDN_PROCESSING и на передачи, а не только на каналы
     */
    public static List<TransferPermission> evaluate(
            List<TransferSnapshots.TransferConsent> consents,
            Map<UUID, TransferSnapshots.Recipient> recipients,
            boolean baseConsentUsable,
            Instant now,
            java.time.ZoneId operatorZone) {

        List<TransferPermission> permissions = new ArrayList<>();
        if (!baseConsentUsable) {
            return permissions;
        }
        for (TransferSnapshots.TransferConsent consent : consents) {
            if (!consent.isUsable() || consent.thirdPartyId() == null) {
                continue;
            }
            TransferSnapshots.Recipient recipient = recipients.get(consent.thirdPartyId());
            if (recipient == null || !recipient.active()) {
                continue;
            }

            List<String> categories = intersect(consent.pdnCategories(), recipient.allowedPdnCategories());
            if (categories.isEmpty()) {
                continue;
            }

            Instant validUntil = earliest(consent.validUntil(), recipient.contractValidUntil());
            boolean contractExpired = recipient.contractValidUntil() != null
                    && recipient.contractValidUntil().isBefore(now);

            permissions.add(new TransferPermission(
                    recipient.id(),
                    recipient.name(),
                    recipient.role(),
                    categories,
                    validUntil,
                    validUntil == null ? null : daysBetween(now, validUntil, operatorZone),
                    consent.consentId(),
                    contractExpired));
        }
        return permissions;
    }

    /** Ответ на точечный вопрос «можно ли передать эти категории этому лицу» (FR-7.3). */
    public record TransferCheck(
            boolean allowed,
            List<String> allowedCategories,
            List<String> deniedCategories,
            Instant validUntil,
            String reason) {}

    public static TransferCheck check(
            List<TransferSnapshots.TransferConsent> consents,
            TransferSnapshots.Recipient recipient,
            List<String> requestedCategories,
            boolean baseConsentUsable,
            Instant now,
            java.time.ZoneId operatorZone) {

        if (!baseConsentUsable) {
            return new TransferCheck(
                    false, List.of(), requestedCategories, null, "Нет действующего согласия на обработку ПДн");
        }
        if (recipient == null || !recipient.active()) {
            return new TransferCheck(false, List.of(), requestedCategories, null, "Третье лицо неактивно");
        }
        if (recipient.contractValidUntil() != null
                && recipient.contractValidUntil().isBefore(now)) {
            return new TransferCheck(false, List.of(), requestedCategories, null, "Договор с третьим лицом истёк");
        }

        List<TransferPermission> permissions =
                evaluate(consents, Map.of(recipient.id(), recipient), true, now, operatorZone);
        if (permissions.isEmpty()) {
            return new TransferCheck(
                    false, List.of(), requestedCategories, null, "Нет действующего согласия на передачу этому лицу");
        }

        TransferPermission permission = permissions.get(0);
        List<String> requested = requestedCategories == null || requestedCategories.isEmpty()
                ? permission.allowedCategories()
                : requestedCategories;
        List<String> allowed = intersect(requested, permission.allowedCategories());
        List<String> denied = requested.stream()
                .filter(category -> !allowed.contains(category))
                .toList();

        return new TransferCheck(
                !allowed.isEmpty() && denied.isEmpty(),
                allowed,
                denied,
                permission.validUntil(),
                denied.isEmpty() ? null : "Часть запрошенных категорий не покрыта согласием или договором");
    }

    static List<String> intersect(List<String> first, List<String> second) {
        if (first == null || second == null) {
            return List.of();
        }
        Set<String> allowed = new LinkedHashSet<>(second);
        return first.stream().filter(allowed::contains).distinct().toList();
    }

    static Instant earliest(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isBefore(second) ? first : second;
    }

    private static long daysBetween(Instant now, Instant until, java.time.ZoneId zone) {
        return java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.ofInstant(now, zone), java.time.LocalDate.ofInstant(until, zone));
    }
}
