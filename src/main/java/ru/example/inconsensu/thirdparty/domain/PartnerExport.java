package ru.example.inconsensu.thirdparty.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Запись журнала выгрузок партнёру (FR-7.4). Не редактируется и не удаляется: это доказательство. */
@Entity
@Table(name = "partner_export_log")
public class PartnerExport {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "third_party_id", nullable = false)
    private UUID thirdPartyId;

    @Column(name = "requested_by", length = 128)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "format", nullable = false, length = 16)
    private String format;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filter", nullable = false, columnDefinition = "jsonb")
    private String filter = "{}";

    @Column(name = "records_count", nullable = false)
    private int recordsCount;

    @Column(name = "file_checksum", length = 71)
    private String fileChecksum;

    @Column(name = "content")
    private String content;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected PartnerExport() {
        // for JPA
    }

    public PartnerExport(
            UUID id,
            UUID thirdPartyId,
            String requestedBy,
            Instant requestedAt,
            String format,
            String filter,
            int recordsCount,
            String fileChecksum,
            String content,
            Instant expiresAt) {
        this.id = id;
        this.thirdPartyId = thirdPartyId;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.format = format;
        this.filter = filter == null ? "{}" : filter;
        this.recordsCount = recordsCount;
        this.fileChecksum = fileChecksum;
        this.content = content;
        this.expiresAt = expiresAt;
    }

    /** FR-7.4: ссылка живёт ограниченное время; после этого содержимое недоступно, а запись остаётся. */
    public boolean isDownloadable(Instant now) {
        return content != null && expiresAt.isAfter(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getThirdPartyId() {
        return thirdPartyId;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getFormat() {
        return format;
    }

    public String getFilter() {
        return filter;
    }

    public int getRecordsCount() {
        return recordsCount;
    }

    public String getFileChecksum() {
        return fileChecksum;
    }

    public String getContent() {
        return content;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
