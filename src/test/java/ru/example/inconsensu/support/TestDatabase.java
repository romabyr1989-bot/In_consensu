package ru.example.inconsensu.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Адрес PostgreSQL для интеграционных тестов (§11).
 *
 * <p>База внешняя: продукт ставится на чистую операционную систему и не зависит от Docker, поэтому его
 * проверка тоже не должна поднимать контейнеры (ADR-0078). Разработчик и CI поднимают PostgreSQL сами —
 * пакетом операционной системы или уже установленным сервисом.
 *
 * <p>Значения по умолчанию рассчитаны на локальную установку; в CI и на другой машине переопределяются
 * переменными окружения.
 */
public final class TestDatabase {

    /** Схема, в которой работает основной набор интеграционных тестов. */
    private static final String DEFAULT_SCHEMA = "public";

    private static boolean prepared;

    public static final String URL_VARIABLE = "INCONSENSU_TEST_DB_URL";
    public static final String USER_VARIABLE = "INCONSENSU_TEST_DB_USER";
    public static final String PASSWORD_VARIABLE = "INCONSENSU_TEST_DB_PASSWORD";

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/inconsensu_test";
    private static final String DEFAULT_USER = "inconsensu";
    private static final String DEFAULT_PASSWORD = "inconsensu";

    private TestDatabase() {}

    /**
     * Готовит базу к прогону: один раз на JVM очищает основную схему.
     *
     * <p>Контейнер поднимался пустым на каждый прогон, внешняя база — нет. Без очистки в ней копятся данные
     * прошлых запусков, и тесты начинают проверять чужое состояние: очередь уведомлений, например, отдавала
     * письма прошлых прогонов, а письмо текущего теста не попадало в отправляемую пачку. Такая база делает
     * прогон недетерминированным, поэтому она пересоздаётся (ADR-0078).
     */
    public static synchronized void prepareOnce() {
        if (prepared) {
            return;
        }
        prepared = true;
        resetSchema(DEFAULT_SCHEMA);
    }

    /** Пересоздаёт схему: нужно тестам, которым нужна своя изолированная схема. */
    public static void resetSchema(String schema) {
        try (Connection connection = DriverManager.getConnection(url(), user(), password());
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
            statement.execute("create schema " + schema);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Не удалось подготовить тестовую базу " + url() + ": проверьте " + URL_VARIABLE + " и доступность"
                            + " PostgreSQL (README, docs/install.md)",
                    failure);
        }
    }

    public static String url() {
        return value(URL_VARIABLE, DEFAULT_URL);
    }

    public static String user() {
        return value(USER_VARIABLE, DEFAULT_USER);
    }

    public static String password() {
        return value(PASSWORD_VARIABLE, DEFAULT_PASSWORD);
    }

    /**
     * Адрес той же базы с отдельной схемой.
     *
     * <p>Нужен тестам, которые меняют глобальный режим хранения и не должны задевать соседей: раньше для
     * этого поднимался отдельный контейнер (ADR-0048).
     */
    public static String urlWithSchema(String schema) {
        String url = url();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private static String value(String variable, String fallback) {
        String fromEnvironment = System.getenv(variable);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        return System.getProperty(variable, fallback);
    }
}
