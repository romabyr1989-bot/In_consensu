package ru.example.cus.common.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** §8.7: the database keeps UTC, the API answers in ISO-8601 with the operator's offset (see Приложение A). */
public final class ApiTime {

    private ApiTime() {}

    public static OffsetDateTime at(Instant instant, ZoneId zone) {
        return instant == null ? null : instant.atZone(zone).toOffsetDateTime();
    }
}
