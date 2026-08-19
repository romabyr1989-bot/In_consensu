package ru.example.inconsensu.ui.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.integration.application.ConsentImportService;
import ru.example.inconsensu.integration.domain.ImportJob;

/** Модель экрана импорта (UI-12): задача и разобранный построчный отчёт. */
@Service
public class UiImportViewService {

    private static final TypeReference<List<Map<String, Object>>> REPORT_TYPE = new TypeReference<>() {};

    /** @param report строки отчёта: номер строки, поле, причина отклонения */
    public record JobView(ImportJob job, List<Map<String, Object>> report, int percent) {}

    private final ConsentImportService imports;
    private final ObjectMapper objectMapper;

    public UiImportViewService(ConsentImportService imports, ObjectMapper objectMapper) {
        this.imports = imports;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public JobView job(UUID id) {
        ImportJob job = imports.get(id);
        int total = job.getTotal();
        int done = job.getImported() + job.getRejected();
        return new JobView(job, report(job), total <= 0 ? 100 : Math.min(100, done * 100 / total));
    }

    private List<Map<String, Object>> report(ImportJob job) {
        if (job.getReport() == null || job.getReport().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(job.getReport(), REPORT_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }
}
