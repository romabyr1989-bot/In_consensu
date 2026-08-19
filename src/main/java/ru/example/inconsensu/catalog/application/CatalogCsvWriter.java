package ru.example.inconsensu.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Табличная форма выгрузки каталога (FR-3.3): по одной таблице на типы, формы и пункты. */
@Component
public class CatalogCsvWriter {

    private static final String TYPES_HEADER = "code,nameRu,category,requiresThirdParty,defaultValidity,active,"
            + "activeConsents,expiringConsents,expiredConsents,revokedConsents,expiringSoon";
    private static final String FORMS_HEADER =
            "code,version,title,status,validFrom,validTo,publishedAt,checksum,sourceChannels,items";
    private static final String ITEMS_HEADER = "formCode,formVersion,sortOrder,consentTypeCode,text,"
            + "purposes,pdnCategories,thirdPartyInn,thirdPartyName,validity,mandatory";

    public String write(CatalogExportService.Part part, CatalogExportService.CatalogSnapshot snapshot) {
        return switch (part) {
            case TYPES -> types(snapshot.types());
            case FORMS -> forms(snapshot.forms());
            case ITEMS -> items(snapshot.forms().stream()
                    .flatMap(form -> form.items().stream())
                    .toList());
        };
    }

    private String types(List<CatalogExportService.TypeRow> rows) {
        StringBuilder builder = new StringBuilder(TYPES_HEADER).append('\n');
        for (CatalogExportService.TypeRow row : rows) {
            builder.append(line(
                            row.code(),
                            row.nameRu(),
                            row.category(),
                            String.valueOf(row.requiresThirdParty()),
                            row.defaultValidity(),
                            String.valueOf(row.active()),
                            String.valueOf(row.activeConsents()),
                            String.valueOf(row.expiringConsents()),
                            String.valueOf(row.expiredConsents()),
                            String.valueOf(row.revokedConsents()),
                            String.valueOf(row.expiringSoon())))
                    .append('\n');
        }
        return builder.toString();
    }

    private String forms(List<CatalogExportService.FormRow> rows) {
        StringBuilder builder = new StringBuilder(FORMS_HEADER).append('\n');
        for (CatalogExportService.FormRow row : rows) {
            builder.append(line(
                            row.code(),
                            String.valueOf(row.version()),
                            row.title(),
                            row.status(),
                            instant(row.validFrom()),
                            instant(row.validTo()),
                            instant(row.publishedAt()),
                            row.checksum(),
                            String.join(" ", row.sourceChannels()),
                            String.valueOf(row.items().size())))
                    .append('\n');
        }
        return builder.toString();
    }

    private String items(List<CatalogExportService.ItemRow> rows) {
        StringBuilder builder = new StringBuilder(ITEMS_HEADER).append('\n');
        for (CatalogExportService.ItemRow row : rows) {
            builder.append(line(
                            row.formCode(),
                            String.valueOf(row.formVersion()),
                            String.valueOf(row.sortOrder()),
                            row.consentTypeCode(),
                            row.text(),
                            String.join(" ", row.purposes()),
                            String.join(" ", row.pdnCategories()),
                            row.thirdPartyInn(),
                            row.thirdPartyName(),
                            row.validity(),
                            String.valueOf(row.mandatory())))
                    .append('\n');
        }
        return builder.toString();
    }

    private static String instant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String line(String... values) {
        return java.util.Arrays.stream(values).map(CatalogCsvWriter::quote).collect(Collectors.joining(","));
    }

    /** Тексты пунктов и названия содержат запятые, кавычки и переносы: без экранирования файл развалится. */
    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        return needsQuotes ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
