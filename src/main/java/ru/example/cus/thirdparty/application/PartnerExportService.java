package ru.example.cus.thirdparty.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.audit.application.AuditService;
import ru.example.cus.audit.application.PdnAccessLogService;
import ru.example.cus.common.domain.AuditEventType;
import ru.example.cus.common.error.ApiException;
import ru.example.cus.common.error.ErrorCode;
import ru.example.cus.common.security.CurrentUser;
import ru.example.cus.iam.application.OperatorSettingsService;
import ru.example.cus.thirdparty.domain.PartnerExport;
import ru.example.cus.thirdparty.domain.PartnerExportDataPort;
import ru.example.cus.thirdparty.domain.ThirdParty;
import ru.example.cus.thirdparty.infrastructure.PartnerExportRepository;

/**
 * «Данные для партнёров»: выгрузка субъектов с действующим согласием на передачу (FR-7.4).
 *
 * <p>В файл попадают только те категории, которые разрешены и согласием, и договором. Каждая выгрузка
 * фиксируется в журнале с контрольной суммой содержимого: через год нужно уметь показать, что именно ушло.
 */
@Service
public class PartnerExportService {

    public static final String AGGREGATE_TYPE = "partner_export";

    private static final String TTL_SETTING = "cus.export.ttl";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final PartnerExportRepository exports;
    private final PartnerExportDataPort data;
    private final ThirdPartyService thirdParties;
    private final OperatorSettingsService settings;
    private final AuditService auditService;
    private final PdnAccessLogService pdnAccessLog;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PartnerExportService(
            PartnerExportRepository exports,
            PartnerExportDataPort data,
            ThirdPartyService thirdParties,
            OperatorSettingsService settings,
            AuditService auditService,
            PdnAccessLogService pdnAccessLog,
            ObjectMapper objectMapper,
            Clock clock) {
        this.exports = exports;
        this.data = data;
        this.thirdParties = thirdParties;
        this.settings = settings;
        this.auditService = auditService;
        this.pdnAccessLog = pdnAccessLog;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public PartnerExport create(UUID thirdPartyId, String format) {
        ThirdParty thirdParty = thirdParties.get(thirdPartyId);
        if (!thirdParty.canReceiveData(thirdParties.today())) {
            throw new ApiException(
                    ErrorCode.CONFLICT, "Выгрузка невозможна: третье лицо неактивно или договор с ним истёк (FR-7.1)");
        }
        String normalizedFormat = format == null ? "csv" : format.toLowerCase(java.util.Locale.ROOT);
        if (!List.of("csv", "json").contains(normalizedFormat)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Поддерживаются форматы csv и json");
        }

        Instant now = clock.instant();
        Set<String> allowed = thirdParty.getAllowedPdnCategories();
        List<PartnerExportDataPort.ExportRow> rows = data.rowsFor(thirdPartyId, allowed, now);
        String content = "csv".equals(normalizedFormat) ? toCsv(rows, allowed) : toJson(rows);

        PartnerExport export = exports.save(new PartnerExport(
                UUID.randomUUID(),
                thirdPartyId,
                CurrentUser.login(),
                now,
                normalizedFormat,
                toJson(Map.of("categories", List.copyOf(allowed))),
                rows.size(),
                checksum(content),
                content,
                now.plus(ttl())));

        pdnAccessLog.recordBulk("/api/v1/third-parties/{id}/exports", rows.size());
        auditService.record(
                AGGREGATE_TYPE,
                export.getId().toString(),
                AuditEventType.EXPORTED,
                Map.of(
                        "thirdPartyInn", thirdParty.getInn(),
                        "format", normalizedFormat,
                        "records", rows.size(),
                        "categories", List.copyOf(allowed),
                        "checksum", String.valueOf(export.getFileChecksum())));
        return export;
    }

    @Transactional(readOnly = true)
    public List<PartnerExport> listFor(UUID thirdPartyId) {
        return exports.findByThirdPartyIdOrderByRequestedAtDesc(thirdPartyId);
    }

    @Transactional(readOnly = true)
    public PartnerExport download(UUID exportId) {
        PartnerExport export =
                exports.findById(exportId).orElseThrow(() -> ApiException.notFound("Выгрузка не найдена"));
        if (!export.isDownloadable(clock.instant())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Срок хранения выгрузки истёк");
        }
        pdnAccessLog.recordBulk("/api/v1/exports/{id}/download", export.getRecordsCount());
        return export;
    }

    private Duration ttl() {
        String configured = settings.value(TTL_SETTING);
        try {
            return configured == null || configured.isBlank() ? DEFAULT_TTL : Duration.parse(configured.trim());
        } catch (Exception e) {
            return DEFAULT_TTL;
        }
    }

    private String toCsv(List<PartnerExportDataPort.ExportRow> rows, Set<String> categories) {
        List<String> columns = new ArrayList<>();
        columns.add("external_id");
        columns.addAll(categories);

        StringBuilder builder = new StringBuilder(String.join(",", columns)).append('\n');
        for (PartnerExportDataPort.ExportRow row : rows) {
            List<String> cells = new ArrayList<>();
            cells.add(quote(row.externalId()));
            categories.forEach(category -> cells.add(quote(row.values().getOrDefault(category, ""))));
            builder.append(String.join(",", cells)).append('\n');
        }
        return builder.toString();
    }

    /** Значения экранируются: ФИО и адреса содержат запятые, и без кавычек файл развалится у получателя. */
    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        return value.contains(",") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Не удалось сформировать выгрузку");
        }
    }

    private String toJson(List<PartnerExportDataPort.ExportRow> rows) {
        List<Map<String, String>> plain = new ArrayList<>();
        for (PartnerExportDataPort.ExportRow row : rows) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("external_id", row.externalId());
            entry.putAll(row.values());
            plain.add(entry);
        }
        return toJson((Object) plain);
    }

    private static String checksum(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 требуется по FR-7.4, но недоступен", e);
        }
    }
}
