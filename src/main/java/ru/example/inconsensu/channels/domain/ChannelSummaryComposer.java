package ru.example.inconsensu.channels.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.example.inconsensu.common.domain.ChannelDenyReason;
import ru.example.inconsensu.common.domain.CommunicationChannel;

/**
 * Человекочитаемая сводка по каналам (FR-6.1, {@code summaryRu}).
 *
 * <p>Менеджеру колл-центра нужен ответ фразой, а не таблицей флагов: он читает её вслух перед звонком.
 * Поэтому сводка называет разрешённые действия и отдельно — запреты, у которых есть внятная причина.
 */
public final class ChannelSummaryComposer {

    private static final Map<CommunicationChannel, String> ALLOWED_PHRASES = Map.of(
            CommunicationChannel.PHONE_CALL, "звонить",
            CommunicationChannel.SMS, "отправлять SMS",
            CommunicationChannel.EMAIL, "писать на электронную почту",
            CommunicationChannel.PUSH, "отправлять push-уведомления",
            CommunicationChannel.MESSENGER, "писать в мессенджер",
            CommunicationChannel.POSTAL_MAIL, "отправлять почтовую рассылку");

    private static final Map<CommunicationChannel, String> DENIED_SUBJECTS = Map.of(
            CommunicationChannel.PHONE_CALL, "Звонки запрещены",
            CommunicationChannel.SMS, "Рассылка SMS запрещена",
            CommunicationChannel.EMAIL, "Реклама по email запрещена",
            CommunicationChannel.PUSH, "Push-уведомления запрещены",
            CommunicationChannel.MESSENGER, "Сообщения в мессенджер запрещены",
            CommunicationChannel.POSTAL_MAIL, "Почтовая рассылка запрещена");

    private static final Map<ChannelDenyReason, String> REASONS = Map.of(
            ChannelDenyReason.REVOKED, "согласие отозвано",
            ChannelDenyReason.EXPIRED, "срок действия согласия истёк",
            ChannelDenyReason.NO_CONSENT, "нет согласия",
            ChannelDenyReason.BASE_CONSENT_MISSING, "нет базового согласия на обработку персональных данных");

    private ChannelSummaryComposer() {}

    public static String compose(List<ChannelDecision> decisions) {
        if (decisions.stream().allMatch(decision -> decision.reason() == ChannelDenyReason.BASE_CONSENT_MISSING)) {
            return "Связываться с клиентом нельзя: нет базового согласия на обработку персональных данных.";
        }

        List<String> sentences = new ArrayList<>();

        List<String> allowed = decisions.stream()
                .filter(ChannelDecision::allowed)
                .map(decision -> ALLOWED_PHRASES.get(decision.channel()))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (allowed.isEmpty()) {
            sentences.add("Связываться с клиентом нельзя ни по одному каналу.");
        } else {
            sentences.add("Можно " + String.join(", ", allowed) + ".");
        }

        // Отдельно называются запреты, у которых есть история: «нет согласия» повторять по каждому каналу незачем.
        decisions.stream()
                .filter(decision -> !decision.allowed())
                .filter(decision -> decision.reason() == ChannelDenyReason.REVOKED
                        || decision.reason() == ChannelDenyReason.EXPIRED)
                .forEach(decision -> sentences.add(
                        DENIED_SUBJECTS.get(decision.channel()) + ": " + REASONS.get(decision.reason()) + "."));

        return String.join(" ", sentences);
    }

    public static String reasonText(ChannelDenyReason reason) {
        return reason == null ? null : REASONS.get(reason);
    }
}
