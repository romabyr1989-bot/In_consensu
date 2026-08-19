package ru.example.cus.channels.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.example.cus.channels.application.ChannelService;
import ru.example.cus.channels.domain.ChannelDecision;
import ru.example.cus.channels.domain.ChannelSummaryComposer;
import ru.example.cus.common.api.ApiTime;
import ru.example.cus.common.api.ChannelView;
import ru.example.cus.common.config.CusProperties;
import ru.example.cus.common.domain.CommunicationChannel;

/** §9: массовая проверка каналов для систем рассылок (FR-6.4). */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('MANAGER','MARKETING','INTEGRATION','DPO','ADMIN')")
public class ChannelController {

    public record BulkCheckRequest(
            @NotNull CommunicationChannel channel, @NotEmpty List<String> identifiers, boolean includeReasons) {}

    public record BulkCheckResponse(
            String channel,
            int requested,
            int allowedCount,
            List<String> allowed,
            Map<String, String> deniedReasons,
            List<String> unknownIdentifiers) {}

    private final ChannelService channels;
    private final ru.example.cus.channels.application.BulkCheckJobService bulkJobs;
    private final CusProperties properties;

    public ChannelController(
            ChannelService channels,
            ru.example.cus.channels.application.BulkCheckJobService bulkJobs,
            CusProperties properties) {
        this.channels = channels;
        this.bulkJobs = bulkJobs;
        this.properties = properties;
    }

    /** FR-6.1: разрешённые каналы коммуникации по одному субъекту. */
    @org.springframework.web.bind.annotation.GetMapping("/subjects/{id}/channels")
    public SubjectChannelsResponse channelsOf(@org.springframework.web.bind.annotation.PathVariable UUID id) {
        var evaluated = channels.channelsOf(id);
        return new SubjectChannelsResponse(
                evaluated.decisions().stream().map(this::toView).toList(), evaluated.summaryRu());
    }

    public record SubjectChannelsResponse(List<ChannelView> channels, String summaryRu) {}

    /** @param jobId идентификатор задачи: по нему забирается результат */
    public record BulkJobResponse(
            java.util.UUID jobId,
            String channel,
            String status,
            String statusRu,
            int requested,
            int processed,
            int allowedCount,
            String result,
            String error) {

        static BulkJobResponse of(ru.example.cus.channels.domain.BulkCheckJob job) {
            return new BulkJobResponse(
                    job.getId(),
                    job.getChannel().name(),
                    job.getStatus().name(),
                    job.getStatus().nameRu(),
                    job.getRequested(),
                    job.getProcessed(),
                    job.getAllowedCount(),
                    job.getResult(),
                    job.getError());
        }
    }

    /**
     * Асинхронная массовая проверка (этап 8): для рассылки по всей базе, когда синхронный предел FR-6.4
     * в 10 000 идентификаторов мал.
     */
    @PreAuthorize("hasAnyRole('MARKETING','INTEGRATION','DPO','ADMIN')")
    @PostMapping("/channels/check-async")
    public BulkJobResponse checkAsync(@Valid @RequestBody BulkCheckRequest request) {
        return BulkJobResponse.of(bulkJobs.submit(request.channel(), request.identifiers()));
    }

    @PreAuthorize("hasAnyRole('MARKETING','INTEGRATION','DPO','ADMIN')")
    @org.springframework.web.bind.annotation.GetMapping("/channels/check-async/{jobId}")
    public BulkJobResponse checkAsyncResult(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID jobId) {
        return BulkJobResponse.of(bulkJobs.get(jobId));
    }

    /**
     * Выгрузка результата массовой проверки файлом (FR-6.4, этап 8).
     *
     * <p>Рассылке нужен не JSON на миллион записей, а файл, который можно передать своей системе: отдаётся
     * CSV с идентификатором, решением и причиной запрета.
     */
    @PreAuthorize("hasAnyRole('MARKETING','INTEGRATION','DPO','ADMIN')")
    @org.springframework.web.bind.annotation.GetMapping(
            value = "/channels/check-async/{jobId}/download",
            produces = "text/csv")
    public org.springframework.http.ResponseEntity<String> checkAsyncDownload(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID jobId) {
        return org.springframework.http.ResponseEntity.ok()
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"channel-check-" + jobId + ".csv\"")
                .contentType(
                        new org.springframework.http.MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(bulkJobs.toCsv(jobId));
    }

    /**
     * Массовая проверка (FR-6.4).
     *
     * <p>Роли уже, чем у одиночной проверки: по Приложению E массовая проверка — работа маркетинга и
     * интеграций, менеджеру колл-центра положена только одиночная. §9 перечисляет роли одним списком на обе
     * операции; выбрано более строгое прочтение (ADR-0042, вопрос 18).
     */
    @PreAuthorize("hasAnyRole('MARKETING','INTEGRATION','DPO','ADMIN')")
    @PostMapping("/channels/check")
    public BulkCheckResponse check(@Valid @RequestBody BulkCheckRequest request) {
        var result = channels.check(request.channel(), request.identifiers(), request.includeReasons());
        return new BulkCheckResponse(
                result.channel().name(),
                request.identifiers().size(),
                result.allowed().size(),
                result.allowed(),
                result.deniedReasons(),
                result.unknownIdentifiers());
    }

    /**
     * Перевод решения по каналу в ответ API.
     *
     * <p>Отображение живёт в слое api, а не в домене: правило §7.6 не должно знать про формат ответа (§5).
     * Такой же метод есть в карточке клиента — небольшое повторение дешевле, чем зависимость домена от
     * представления или взаимная зависимость модулей.
     */
    private ChannelView toView(ChannelDecision decision) {
        return new ChannelView(
                decision.channel().name(),
                decision.channel().nameRu(),
                decision.allowed(),
                decision.basis() == null
                        ? null
                        : new ChannelView.Basis(
                                decision.basis().consentId(),
                                decision.basis().typeCode(),
                                ApiTime.at(decision.basis().validUntil(), properties.timezone())),
                decision.reason() == null ? null : decision.reason().name(),
                ChannelSummaryComposer.reasonText(decision.reason()));
    }
}
