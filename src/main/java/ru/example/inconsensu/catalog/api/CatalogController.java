package ru.example.inconsensu.catalog.api;

import io.swagger.v3.oas.annotations.Operation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.catalog.application.CatalogCsvWriter;
import ru.example.inconsensu.catalog.application.CatalogExportService;
import ru.example.inconsensu.catalog.application.CatalogStatsService;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;
import ru.example.inconsensu.common.security.Authorities;

/** §9: статистика и экспорт каталога. Доступны всем сотрудникам. */
@RestController
@RequestMapping("/api/v1/catalog")
// Статистика и выгрузка каталога — «все сотрудники» §9, то есть без INTEGRATION.
@PreAuthorize(Authorities.EMPLOYEE)
public class CatalogController {

    private final CatalogStatsService stats;
    private final CatalogExportService export;
    private final CatalogCsvWriter csv;

    public CatalogController(CatalogStatsService stats, CatalogExportService export, CatalogCsvWriter csv) {
        this.stats = stats;
        this.export = export;
        this.csv = csv;
    }

    @GetMapping("/stats")
    @Operation(
            summary = "Статистика каталога",
            description = "Плитки дашборда UI-2 и разрезы по типам согласий и третьим лицам (FR-3.4)")
    public CatalogStatsService.CatalogStats stats() {
        return stats.stats();
    }

    /**
     * Выгрузка каталога (FR-3.3).
     *
     * <p>json отдаёт каталог целиком: типы, формы и вложенные пункты. csv — таблицу одной части: у типов,
     * форм и пунктов разные колонки, и склейка их в один файл нечитаема ни человеком, ни Excel.
     */
    @GetMapping("/export")
    @Operation(summary = "Экспорт каталога", description = "Типы, формы и пункты форм в формате csv или json")
    public ResponseEntity<?> export(
            @RequestParam(defaultValue = "csv") String format, @RequestParam(defaultValue = "types") String part) {
        String normalizedFormat = format.toLowerCase(Locale.ROOT);
        if (!List.of("csv", "json").contains(normalizedFormat)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Поддерживаются форматы csv и json");
        }
        CatalogExportService.CatalogSnapshot snapshot = export.snapshot();
        if ("json".equals(normalizedFormat)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"catalog.json\"")
                    .body(snapshot);
        }
        CatalogExportService.Part normalizedPart = part(part);
        String filename = "catalog-" + normalizedPart.name().toLowerCase(Locale.ROOT) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv.write(normalizedPart, snapshot));
    }

    private static CatalogExportService.Part part(String value) {
        try {
            return CatalogExportService.Part.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAPart) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Поддерживаются части выгрузки types, forms и items");
        }
    }
}
