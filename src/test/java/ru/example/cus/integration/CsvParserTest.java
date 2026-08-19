package ru.example.cus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.example.cus.integration.domain.CsvParser;

/** FR-4.5: разбор выгрузок, какими они приходят из 1С и Excel, а не какими хотелось бы. */
class CsvParserTest {

    @Test
    void reads_a_simple_file_with_a_header() {
        List<Map<String, String>> rows = CsvParser.parseWithHeader("external_id,last_name\nCRM-1,Травин\nCRM-2,Чкалов");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("external_id", "CRM-1").containsEntry("last_name", "Травин");
        assertThat(rows.get(1)).containsEntry("last_name", "Чкалов");
    }

    @Test
    void header_is_case_insensitive() {
        List<Map<String, String>> rows = CsvParser.parseWithHeader("External_ID,Last_Name\nCRM-1,Травин");

        assertThat(rows.get(0)).containsKey("external_id").containsKey("last_name");
    }

    @Test
    void value_in_quotes_may_contain_the_delimiter() {
        List<Map<String, String>> rows = CsvParser.parseWithHeader("external_id,note\nCRM-1,\"договор, приложение 2\"");

        assertThat(rows.get(0)).containsEntry("note", "договор, приложение 2");
    }

    @Test
    void doubled_quote_inside_a_quoted_value_becomes_one() {
        List<Map<String, String>> rows = CsvParser.parseWithHeader("external_id,note\nCRM-1,\"ООО \"\"Моменто\"\"\"");

        assertThat(rows.get(0)).containsEntry("note", "ООО \"Моменто\"");
    }

    @Test
    void quoted_value_may_contain_a_line_break() {
        List<Map<String, String>> rows = CsvParser.parseWithHeader("external_id,note\nCRM-1,\"первая\nвторая\"");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("note")).contains("первая").contains("вторая");
    }

    @Test
    void semicolon_is_detected_as_a_delimiter() {
        List<Map<String, String>> rows = CsvParser.parseWithHeader("external_id;last_name\nCRM-1;Травин");

        assertThat(rows.get(0)).containsEntry("last_name", "Травин");
    }

    @Test
    void windows_line_endings_and_byte_order_mark_are_tolerated() {
        List<Map<String, String>> rows = CsvParser.parseWithHeader("﻿external_id,last_name\r\nCRM-1,Травин\r\n");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("external_id", "CRM-1");
    }

    @Test
    void missing_trailing_columns_become_empty_values() {
        List<Map<String, String>> rows = CsvParser.parseWithHeader("external_id,last_name,email\nCRM-1,Травин");

        assertThat(rows.get(0)).containsEntry("email", "");
    }

    @Test
    void blank_lines_are_skipped_and_empty_file_yields_nothing() {
        assertThat(CsvParser.parseWithHeader("external_id\nCRM-1\n\n")).hasSize(1);
        assertThat(CsvParser.parseWithHeader("")).isEmpty();
    }
}
