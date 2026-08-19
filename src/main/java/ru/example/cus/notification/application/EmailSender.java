package ru.example.cus.notification.application;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import ru.example.cus.common.config.CusProperties;

/**
 * Отправка HTML-письма по шаблону Thymeleaf (FR-9.2).
 *
 * <p>{@link JavaMailSender} берётся через {@link ObjectProvider}: без настроенного SMTP приложение обязано
 * стартовать и работать, а уведомления — оставаться в журнале со статусом «не отправлено», а не ронять
 * старт контекста.
 */
@Component
public class EmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(EmailSender.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final TemplateEngine templateEngine;
    private final CusProperties properties;

    public EmailSender(
            ObjectProvider<JavaMailSender> mailSender, TemplateEngine templateEngine, CusProperties properties) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.properties = properties;
    }

    /** Собирает HTML по шаблону; сам по себе ничего не отправляет — тело сохраняется в журнале уведомлений. */
    public String render(String template, Map<String, Object> model) {
        Context context =
                new Context(new java.util.Locale.Builder().setLanguage("ru").build());
        context.setVariable("baseUrl", properties.notifications().baseUrl());
        model.forEach(context::setVariable);
        return templateEngine.process("email/" + template, context);
    }

    /**
     * Отправляет письмо. Возвращает {@code null} при успехе и текст ошибки при неудаче — исключение здесь
     * означало бы откат транзакции, в которой уже записано состояние уведомления.
     */
    public String send(String to, String subject, String html) {
        if (!properties.notifications().mail().enabled()) {
            LOG.debug("Отправка писем выключена настройкой, уведомление остаётся в очереди");
            return "Отправка писем выключена настройкой cus.notifications.mail.enabled";
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return "SMTP не настроен: задайте spring.mail.host";
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.notifications().mail().from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            return null;
        } catch (Exception e) {
            // В журнал попадает класс и текст ошибки SMTP; адрес получателя уже хранится в записи уведомления.
            LOG.warn("Не удалось отправить уведомление: {}", e.getClass().getSimpleName());
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Письмо с вложением: дайджест уходит вместе с CSV (FR-9.2). */
    public String sendWithAttachment(String to, String subject, String html, String fileName, byte[] attachment) {
        if (!properties.notifications().mail().enabled()) {
            return "Отправка писем выключена настройкой cus.notifications.mail.enabled";
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return "SMTP не настроен: задайте spring.mail.host";
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.notifications().mail().from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            helper.addAttachment(fileName, new org.springframework.core.io.ByteArrayResource(attachment));
            sender.send(message);
            return null;
        } catch (Exception e) {
            LOG.warn("Не удалось отправить дайджест: {}", e.getClass().getSimpleName());
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
