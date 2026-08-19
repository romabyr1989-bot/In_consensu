package ru.example.inconsensu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.example.inconsensu.support.AbstractIntegrationTest;

/** §11: migrations apply to a clean database and leave nothing pending. */
class FlywayMigrationsIT extends AbstractIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void all_migrations_are_applied_and_none_is_pending() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void baseline_creates_the_shedlock_table() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = ?",
                Integer.class,
                "shedlock");

        assertThat(tables).isEqualTo(1);
    }

    @Test
    void baseline_follows_the_timestamp_conventions_of_the_data_model() {
        // §6: every timestamp is timestamptz. Checked here so the very first migration pins the convention.
        List<String> timestampColumns = jdbcTemplate.queryForList(
                """
                select data_type
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'shedlock'
                  and column_name in ('lock_until', 'locked_at')
                """,
                String.class);

        assertThat(timestampColumns).hasSize(2).allSatisfy(type -> assertThat(type)
                .isEqualTo("timestamp with time zone"));
    }
}
