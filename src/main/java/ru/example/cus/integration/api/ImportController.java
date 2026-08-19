package ru.example.cus.integration.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.example.cus.common.api.ApiTime;
import ru.example.cus.common.api.PageResponse;
import ru.example.cus.common.config.CusProperties;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.common.error.ErrorCode;
import ru.example.cus.integration.application.ConsentImportService;
import ru.example.cus.integration.domain.ImportJob;

/** §9: импорт согласий из базы клиентов (FR-4.5). */
@RestController
@RequestMapping("/api/v1/import")
@PreAuthorize("hasAnyRole('INTEGRATION','ADMIN','DPO')")
public class ImportController {

    /** Задача импорта в ответе API (UI-12). */
    public record ImportJobResponse(
            UUID id,
            String source,
            String fileName,
            boolean dryRun,
            String status,
            String statusRu,
            int total,
            int imported,
            int rejected,
            String startedBy,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {

        static ImportJobResponse of(ImportJob job, ZoneId zone) {
            return new ImportJobResponse(
                    job.getId(),
                    job.getSource(),
                    job.getFileName(),
                    job.isDryRun(),
                    job.getStatus().name(),
                    job.getStatus().nameRu(),
                    job.getTotal(),
                    job.getImported(),
                    job.getRejected(),
                    job.getStartedBy(),
                    ApiTime.at(job.getStartedAt(), zone),
                    ApiTime.at(job.getFinishedAt(), zone));
        }
    }

    private final ConsentImportService importService;
    private final CusProperties properties;
    private final ObjectMapper objectMapper;

    public ImportController(ConsentImportService importService, CusProperties properties, ObjectMapper objectMapper) {
        this.importService = importService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * FR-4.5: файл принимается целиком, обработка идёт асинхронно.
     *
     * <p>{@code dryRun} включён по умолчанию (UI-12): случайно запустить боевой импорт исторических согласий
     * должно быть труднее, чем проверить файл.
     */
    @PostMapping(value = "/consents", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportJobResponse importConsents(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "source", defaultValue = "CLIENT_BASE_IMPORT") String source,
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Файл импорта пуст");
        }
        try {
            ImportJob job = importService.start(file.getOriginalFilename(), file.getBytes(), source, dryRun);
            return ImportJobResponse.of(job, properties.timezone());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Не удалось прочитать файл импорта");
        }
    }

    @GetMapping("/jobs")
    public PageResponse<ImportJobResponse> jobs(@PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(importService.list(pageable), job -> ImportJobResponse.of(job, properties.timezone()));
    }

    @GetMapping("/jobs/{id}")
    public ImportJobResponse job(@PathVariable UUID id) {
        return ImportJobResponse.of(importService.get(id), properties.timezone());
    }

    /** Построчный отчёт об ошибках: номер строки, поле, причина (FR-4.5, UI-12). */
    @GetMapping("/jobs/{id}/report")
    public JsonNode report(@PathVariable UUID id) {
        try {
            return objectMapper.readTree(importService.get(id).getReport());
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }
}
