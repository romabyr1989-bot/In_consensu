package ru.example.cus.thirdparty.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.example.cus.common.api.ApiTime;
import ru.example.cus.common.config.CusProperties;
import ru.example.cus.thirdparty.application.PartnerExportService;
import ru.example.cus.thirdparty.domain.PartnerExport;

/** §9: «Данные для партнёров» (FR-7.4). Доступ — ADMIN, DPO, INTEGRATION по Приложению E. */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADMIN','DPO','INTEGRATION')")
public class PartnerExportController {

    public record ExportResponse(
            UUID id,
            UUID thirdPartyId,
            String format,
            int recordsCount,
            String fileChecksum,
            String requestedBy,
            OffsetDateTime requestedAt,
            OffsetDateTime expiresAt,
            boolean downloadable) {}

    private final PartnerExportService exports;
    private final CusProperties properties;
    private final java.time.Clock clock;

    public PartnerExportController(PartnerExportService exports, CusProperties properties, java.time.Clock clock) {
        this.exports = exports;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/third-parties/{id}/exports")
    @ResponseStatus(HttpStatus.CREATED)
    public ExportResponse create(
            @PathVariable UUID id, @RequestParam(name = "format", defaultValue = "csv") String format) {
        return toResponse(exports.create(id, format));
    }

    @GetMapping("/third-parties/{id}/exports")
    public List<ExportResponse> list(@PathVariable UUID id) {
        return exports.listFor(id).stream().map(this::toResponse).toList();
    }

    /** Ссылка живёт ограниченное время: после истечения TTL остаётся только запись в журнале (FR-7.4). */
    @GetMapping("/exports/{id}/download")
    public ResponseEntity<String> download(@PathVariable UUID id) {
        PartnerExport export = exports.download(id);
        String extension = "json".equals(export.getFormat()) ? "json" : "csv";
        MediaType mediaType = "json".equals(export.getFormat())
                ? MediaType.APPLICATION_JSON
                : MediaType.parseMediaType("text/csv; charset=UTF-8");

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("export-" + export.getId() + "." + extension)
                                .build()
                                .toString())
                // Контрольная сумма рядом с файлом: получатель может убедиться, что скачал именно то (FR-7.4).
                .header("X-Cus-File-Checksum", String.valueOf(export.getFileChecksum()))
                .body(export.getContent());
    }

    private ExportResponse toResponse(PartnerExport export) {
        return new ExportResponse(
                export.getId(),
                export.getThirdPartyId(),
                export.getFormat(),
                export.getRecordsCount(),
                export.getFileChecksum(),
                export.getRequestedBy(),
                ApiTime.at(export.getRequestedAt(), properties.timezone()),
                ApiTime.at(export.getExpiresAt(), properties.timezone()),
                export.isDownloadable(clock.instant()));
    }
}
