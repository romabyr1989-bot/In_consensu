package ru.example.inconsensu.ui.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

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

    /** Начало календарного дня в таймзоне оператора: фильтры форм задаются датами, а не мгновениями. */
    public Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(zone).toInstant();
    }

    /**
     * Срок действия пункта формы по-русски: «1 год», «6 месяцев», «до отзыва» (UI-0.4).
     *
     * <p>В базе срок лежит периодом ISO-8601 («P1Y»), и до сих пор он так и печатался на экранах — то есть
     * сотрудник видел технический код вместо срока.
     */
    public String period(String isoPeriod) {
        if (isoPeriod == null || isoPeriod.isBlank()) {
            return "до отзыва";
        }
        Period period;
        try {
            period = Period.parse(isoPeriod);
        } catch (DateTimeParseException notAPeriod) {
            return isoPeriod;
        }
        List<String> parts = new ArrayList<>();
        if (period.getYears() != 0) {
            parts.add(plural(period.getYears(), "год", "года", "лет"));
        }
        if (period.getMonths() != 0) {
            parts.add(plural(period.getMonths(), "месяц", "месяца", "месяцев"));
        }
        if (period.getDays() != 0) {
            parts.add(plural(period.getDays(), "день", "дня", "дней"));
        }
        return parts.isEmpty() ? "до отзыва" : String.join(" ", parts);
    }

    /** Русское склонение после числа: 1 год, 2 года, 5 лет. */
    private static String plural(int amount, String one, String few, String many) {
        int absolute = Math.abs(amount);
        int lastTwo = absolute % 100;
        int last = absolute % 10;
        String noun;
        if (lastTwo >= 11 && lastTwo <= 14) {
            noun = many;
        } else if (last == 1) {
            noun = one;
        } else if (last >= 2 && last <= 4) {
            noun = few;
        } else {
            noun = many;
        }
        return amount + " " + noun;
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
