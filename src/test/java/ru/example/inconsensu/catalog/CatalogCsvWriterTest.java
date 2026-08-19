package ru.example.inconsensu.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.example.inconsensu.catalog.application.CatalogCsvWriter;
import ru.example.inconsensu.catalog.application.CatalogExportService;

/** FR-3.3: таблицы выгрузки каталога с экранированием текста пунктов. */
class CatalogCsvWriterTest {

    private final CatalogCsvWriter writer = new CatalogCsvWriter();

    private static CatalogExportService.CatalogSnapshot snapshot() {
        var item = new CatalogExportService.ItemRow(
                UUID.randomUUID(),
                "FORM_A",
                2,
                0,
                "PDN_PROCESSING",
                "Я, субъект, согласен на обработку: сбор, запись, хранение",
                List.of("рассмотрение заявки"),
                List.of("FIO", "PHONE"),
                "7701234567",
                "ООО «Партнёр, и Ко»",
                "P1Y",
                true);
        var form = new CatalogExportService.FormRow(
                UUID.randomUUID(),
                "FORM_A",
                2,
                "Форма \"А\"",
                "PUBLISHED",
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                Instant.parse("2026-01-01T00:00:00Z"),
                "sha256:abc",
                List.of("WEBSITE_APPLICATION"),
                List.of(item));
        var type = new CatalogExportService.TypeRow(
                "PDN_PROCESSING", "Обработка ПДн", "PDN", false, null, true, 5, 2, 1, 3, 2);
        return new CatalogExportService.CatalogSnapshot(
                Instant.parse("2026-02-01T00:00:00Z"), List.of(type), List.of(form));
    }

    @Test
    void type_table_carries_counts_of_every_status() {
        String csv = writer.write(CatalogExportService.Part.TYPES, snapshot());

        assertThat(csv.lines().toList())
                .containsExactly(
                        "code,nameRu,category,requiresThirdParty,defaultValidity,active,"
                                + "activeConsents,expiringConsents,expiredConsents,revokedConsents,expiringSoon",
                        "PDN_PROCESSING,Обработка ПДн,PDN,false,,true,5,2,1,3,2");
    }

    @Test
    void form_table_reports_the_number_of_items() {
        String csv = writer.write(CatalogExportService.Part.FORMS, snapshot());

        assertThat(csv).contains("FORM_A,2,\"Форма \"\"А\"\"\",PUBLISHED,2026-01-01T00:00:00Z,,");
        assertThat(csv.strip()).endsWith(",1");
    }

    @Test
    void item_text_with_commas_and_quotes_stays_one_field() {
        String csv = writer.write(CatalogExportService.Part.ITEMS, snapshot());

        assertThat(csv)
                .contains("\"Я, субъект, согласен на обработку: сбор, запись, хранение\"")
                .contains("\"ООО «Партнёр, и Ко»\"")
                .contains("FIO PHONE");
    }
}
