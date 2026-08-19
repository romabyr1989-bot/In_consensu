package ru.example.inconsensu.ui.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Форматы интерфейса (UI-0.4): дата дд.мм.гггг, дата и время дд.мм.гггг чч:мм, телефон +7 (9xx) xxx-xx-xx.
 *
 * <p>Форматирование живёт здесь, а не в шаблонах: одна и та же дата не должна выглядеть по-разному на
 * соседних экранах.
 */
public final class UiFormats {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final ZoneId zone;

    public UiFormats(ZoneId zone) {
        this.zone = zone;
    }

    public String date(Instant moment) {
        return moment == null ? "" : DATE.format(moment.atZone(zone));
    }

    public String date(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }

    public String dateTime(Instant moment) {
        return moment == null ? "" : DATE_TIME.format(moment.atZone(zone));
    }

    /** Пустой срок в карточке читается как «бессрочно / до отзыва» (UI-4). */
    public String validUntil(Instant moment) {
        return moment == null ? "бессрочно / до отзыва" : date(moment);
    }

    /** Телефон приводится к виду UI-0.4; маскированное значение остаётся как есть. */
    public String phone(String value) {
        if (value == null) {
            return "";
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() != 11 || value.contains("*")) {
            return value;
        }
        return "+%s (%s) %s-%s-%s"
                .formatted(
                        digits.charAt(0),
                        digits.substring(1, 4),
                        digits.substring(4, 7),
                        digits.substring(7, 9),
                        digits.substring(9, 11));
    }
}
