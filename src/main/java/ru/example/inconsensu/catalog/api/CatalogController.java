package ru.example.inconsensu.catalog.api;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.example.inconsensu.catalog.application.CatalogStatsService;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.common.error.ApiException;
import ru.example.inconsensu.common.error.ErrorCode;

/** §9: статистика и экспорт каталога. Доступны всем сотрудникам. */
@RestController
@RequestMapping("/api/v1/catalog")
@PreAuthorize("isAuthenticated()")
public class CatalogController {

    private static final String CSV_HEADER =
            "code,name,category,requiresThirdParty,defaultValidity,active,activeConsents,revokedConsents";

    private final CatalogStatsService stats;

    public CatalogController(CatalogStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/stats")
    @Operation(summary = "Статистика каталога", description = "Плитки дашборда UI-2 и счётчики по типам согласий")
    public CatalogStatsService.CatalogStats stats() {
        return stats.stats();
    }

    @GetMapping("/export")
    @Operation(summary = "Экспорт каталога", description = "Типы согласий со счётчиками в формате csv или json")
    public ResponseEntity<?> export(@RequestParam(defaultValue = "csv") String format) {
        String normalized = format.toLowerCase(java.util.Locale.ROOT);
        if (!List.of("csv", "json").contains(normalized)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Поддерживаются форматы csv и json");
        }
        CatalogStatsService.CatalogStats snapshot = stats.stats();
        if ("json".equals(normalized)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"catalog.json\"")
                    .body(snapshot.byType());
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"catalog.csv\"")
                .body(toCsv(snapshot));
    }

    private String toCsv(CatalogStatsService.CatalogStats snapshot) {
        var counts =
                snapshot.byType().stream().collect(Collectors.toMap(CatalogStatsService.TypeStats::code, type -> type));
        StringBuilder builder = new StringBuilder(CSV_HEADER).append('\n');
        for (ConsentType type : stats.activeTypes()) {
            var typeStats = counts.get(type.getCode());
            builder.append(String.join(
                            ",",
                            type.getCode(),
                            quote(type.getNameRu()),
                            type.getCategory().name(),
                            String.valueOf(type.isRequiresThirdParty()),
                            type.getDefaultValidity() == null ? "" : type.getDefaultValidity(),
                            String.valueOf(type.isActive()),
                            String.valueOf(typeStats == null ? 0 : typeStats.active()),
                            String.valueOf(typeStats == null ? 0 : typeStats.revoked())))
                    .append('\n');
        }
        return builder.toString();
    }

    /** Названия типов содержат запятые: без экранирования файл развалится у получателя. */
    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        return value.contains(",") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
