package ru.example.inconsensu.support;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Почтовая заглушка для тестов (FR-9.2).
 *
 * <p>SMTP-сервер поднимается в самой JVM: продукт ставится на чистую операционную систему и не требует
 * Docker, поэтому и его проверка не должна поднимать контейнеры (ADR-0078). Сервер один на всю JVM —
 * письмо проверяется в нескольких сценариях, а перезапуск на каждый класс тратил бы время впустую.
 *
 * <p>Имя класса сохранено намеренно: оно называет роль в тестах, а не конкретный продукт, и переименование
 * тронуло бы файлы, к почте отношения не имеющие.
 */
public final class Mailpit {

    private static final GreenMail SERVER = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));

    static {
        SERVER.start();
    }

    private Mailpit() {}

    /** Подключает приложение к заглушке и включает отправку писем, выключенную в тестовом профиле. */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> SERVER.getSmtp().getPort());
        registry.add("inconsensu.notifications.mail.enabled", () -> true);
    }

    /** Сколько ждать письма: отправка идёт своим потоком, и на загруженной машине она отстаёт от теста. */
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(15);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /**
     * Письма, содержащие подстроку, как один текст.
     *
     * <p>Возвращается сырое содержимое сообщений: сценарии проверяют и адресата, и тему, и вложение, а
     * искать их по отдельности означало бы повторять разбор MIME в каждом тесте.
     *
     * <p>Поиск ждёт появления письма, а не смотрит почтовый ящик один раз: отправка завершается позже
     * вызова, который её инициировал, и мгновенная проверка давала пустой ящик ровно тогда, когда машина
     * занята. Пустой результат возвращается только по истечении таймаута — тест увидит внятный отказ.
     */
    public static String search(org.springframework.boot.test.web.client.TestRestTemplate rest, String query) {
        long deadline = System.nanoTime() + DELIVERY_TIMEOUT.toNanos();
        while (true) {
            String found = matching(query);
            if (!found.isEmpty() || System.nanoTime() > deadline) {
                return found;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return found;
            }
        }
    }

    /**
     * Сколько писем содержит подстроку.
     *
     * <p>Нужно там, где проверяется не текст письма, а их число: одно письмо-дайджест вместо пачки
     * отдельных (FR-9.2). Ожидание общее с {@link #search}: отправка идёт своим потоком.
     */
    public static int count(String query) {
        long deadline = System.nanoTime() + DELIVERY_TIMEOUT.toNanos();
        while (true) {
            long found = Arrays.stream(SERVER.getReceivedMessages())
                    .map(Mailpit::raw)
                    .filter(message -> message.contains(query))
                    .count();
            if (found > 0 || System.nanoTime() > deadline) {
                return (int) found;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return (int) found;
            }
        }
    }

    private static String matching(String query) {
        return Arrays.stream(SERVER.getReceivedMessages())
                .map(Mailpit::raw)
                .filter(message -> message.contains(query))
                .collect(Collectors.joining("\n"));
    }

    private static String raw(MimeMessage message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            message.writeTo(bytes);
            StringBuilder text = new StringBuilder(bytes.toString(StandardCharsets.UTF_8));
            // Тема, адреса и тело приходят закодированными: base64 для кириллицы, quoted-printable для
            // вложения. Расшифрованные значения дописываются рядом, чтобы сценарий искал их по-русски.
            text.append('\n')
                    .append(jakarta.mail.internet.MimeUtility.decodeText(String.valueOf(message.getSubject())))
                    .append('\n')
                    .append(Arrays.toString(message.getAllRecipients()));
            appendDecoded(text, message);
            return text.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать письмо заглушки", e);
        }
    }

    /** Обходит части письма и дописывает их содержимое и имена вложений в разобранном виде. */
    private static void appendDecoded(StringBuilder text, jakarta.mail.Part part) throws Exception {
        if (part.getFileName() != null) {
            text.append('\n').append(jakarta.mail.internet.MimeUtility.decodeText(part.getFileName()));
        }
        Object content = part.getContent();
        if (content instanceof String body) {
            text.append('\n').append(body);
            return;
        }
        if (content instanceof jakarta.mail.Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                appendDecoded(text, multipart.getBodyPart(index));
            }
        }
    }
}
