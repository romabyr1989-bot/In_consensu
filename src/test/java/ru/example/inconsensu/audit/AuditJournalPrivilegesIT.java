package ru.example.inconsensu.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import ru.example.inconsensu.support.TestDatabase;

/**
 * FR-10.2, вторая линия защиты: у роли приложения нет прав UPDATE и DELETE на журналы.
 *
 * <p>Первую линию — триггер — проверяет {@link AuditJournalIT}. Здесь проверяется отзыв прав, который
 * миграция делает по плейсхолдеру {@code appRole}. Тест повторяет порядок установки из
 * {@code docs/install.md}: администратор заводит роль приложения и выдаёт ей права по умолчанию на
 * будущие таблицы, после чего приложение накатывает миграции своей ролью и отбирает у роли приложения
 * право менять журналы.
 *
 * <p>Схема отдельная и пересоздаётся: проверять нужно именно свежий прогон миграций с плейсхолдером, а
 * общая схема тестов накатана без него. Spring-контекст не поднимается — тесту нужны только Flyway и
 * JDBC.
 */
class AuditJournalPrivilegesIT {

    private static final String SCHEMA = "journal_guard_test";
    private static final String APP_ROLE = "inconsensu_journal_probe";

    /** Журнал -> столбец, по которому строится UPDATE. Список — дословно из FR-10.2. */
    private static final Map<String, String> JOURNALS =
            Map.of("audit_event", "event_type", "audit_anchor", "day", "pdn_access_log", "endpoint");

    /** Код SQLSTATE «insufficient_privilege»: он не зависит от языка сообщений сервера. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Test
    void application_role_cannot_update_or_delete_journals() throws SQLException {
        try (Connection owner = connect()) {
            prepareRoleAndSchema(owner);
            migrateWithAppRole();
            assertJournalsAreReadOnlyForAppRole(owner);
        }
    }

    private void prepareRoleAndSchema(Connection owner) throws SQLException {
        TestDatabase.resetSchema(SCHEMA);
        try (Statement statement = owner.createStatement()) {
            statement.execute("do $$ begin if not exists (select 1 from pg_roles where rolname = '" + APP_ROLE
                    + "') then create role " + APP_ROLE + " nologin; end if; end $$");
            // Членство нужно для SET ROLE ниже: тест не полагается на то, что пользователь — суперпользователь.
            statement.execute("grant " + APP_ROLE + " to current_user");
            statement.execute("grant usage on schema " + SCHEMA + " to " + APP_ROLE);
            statement.execute("alter default privileges in schema " + SCHEMA + " grant all on tables to " + APP_ROLE);
        }
    }

    private void migrateWithAppRole() {
        Flyway.configure()
                .dataSource(TestDatabase.url(), TestDatabase.user(), TestDatabase.password())
                .locations("classpath:db/migration")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .placeholders(Map.of("appRole", APP_ROLE))
                .load()
                .migrate();
    }

    private void assertJournalsAreReadOnlyForAppRole(Connection owner) throws SQLException {
        try (Statement statement = owner.createStatement()) {
            statement.execute("set search_path to " + SCHEMA);
            statement.execute("set role " + APP_ROLE);
            try {
                // Контрольная таблица: право менять данные у роли осталось, отзыв точечный.
                assertThatCode(() -> statement.executeUpdate("update consent_type set sort_order = sort_order"))
                        .doesNotThrowAnyException();

                for (Map.Entry<String, String> journal : JOURNALS.entrySet()) {
                    String table = journal.getKey();
                    String column = journal.getValue();
                    expectPermissionDenied(statement, "update " + table + " set " + column + " = " + column);
                    expectPermissionDenied(statement, "delete from " + table);
                }
            } finally {
                statement.execute("reset role");
            }
        }
    }

    private static void expectPermissionDenied(Statement statement, String sql) {
        assertThatThrownBy(() -> statement.executeUpdate(sql))
                .describedAs("ожидался отказ по правам на «%s»", sql)
                .isInstanceOf(SQLException.class)
                .extracting(thrown -> ((SQLException) thrown).getSQLState())
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(TestDatabase.url(), TestDatabase.user(), TestDatabase.password());
    }
}
