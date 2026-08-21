package ru.example.inconsensu.catalog.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.example.inconsensu.common.domain.AuditableEntity;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.FormStatus;

/**
 * Версионируемый текст согласия (§2, FR-1.2, FR-1.5).
 *
 * <p>Переходы статусов живут здесь, а не в сервисе: «опубликованную форму нельзя править» — это свойство
 * документа, и нарушить его не должен ни API, ни будущий веб-интерфейс, ни импорт.
 */
@Entity
@Table(name = "consent_form")
@AttributeOverride(name = "version", column = @Column(name = "version_lock", nullable = false))
public class ConsentForm extends AuditableEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 64, updatable = false)
    private String code;

    /** Номер версии формы; оптимистичная блокировка живёт в колонке version_lock. */
    @Column(name = "version", nullable = false, updatable = false)
    private int versionNumber;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private FormStatus status = FormStatus.DRAFT;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "processing_actions")
    private String processingActions;

    @Column(name = "revocation_procedure")
    private String revocationProcedure;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_channels", nullable = false, columnDefinition = "text[]")
    private String[] sourceChannels = new String[0];

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "rendered_checksum", length = 71)
    private String renderedChecksum;

    /**
     * Точный текст версии на момент публикации (FR-1.5, FR-1.6).
     *
     * <p>Хранится отдельно от тела с плейсхолдерами: реквизиты оператора и третьих лиц меняются, а текст,
     * под которым клиент дал согласие, меняться не должен.
     */
    @Column(name = "rendered_text")
    private String renderedText;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_version_id")
    private ConsentForm previousVersion;

    @OneToMany(mappedBy = "form", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sortOrder asc")
    private List<ConsentFormItem> items = new ArrayList<>();

    protected ConsentForm() {
        // for JPA
    }

    public ConsentForm(UUID id, String code, int versionNumber, String title, String body) {
        this.id = id;
        this.code = code;
        this.versionNumber = versionNumber;
        this.title = title;
        this.body = body;
    }

    /** Правки допускаются только в черновике (§9, FR-1.5). */
    public boolean isEditable() {
        return status == FormStatus.DRAFT;
    }

    public void edit(
            String title,
            String body,
            String processingActions,
            String revocationProcedure,
            Set<ConsentSource> sourceChannels) {
        requireStatus(FormStatus.DRAFT, "Изменять можно только черновик формы");
        this.title = title;
        this.body = body;
        this.processingActions = processingActions;
        this.revocationProcedure = revocationProcedure;
        this.sourceChannels = sourceChannels == null
                ? new String[0]
                : sourceChannels.stream().map(Enum::name).toArray(String[]::new);
    }

    public void replaceItems(List<ConsentFormItem> newItems) {
        requireStatus(FormStatus.DRAFT, "Изменять пункты можно только в черновике формы");
        items.clear();
        items.addAll(newItems);
    }

    public void submitForReview(Instant moment) {
        requireStatus(FormStatus.DRAFT, "На согласование отправляется только черновик");
        status = FormStatus.ON_REVIEW;
        submittedAt = moment;
    }

    public void approve() {
        requireStatus(FormStatus.ON_REVIEW, "Одобрить можно только форму на согласовании");
        status = FormStatus.APPROVED;
    }

    public void returnToDraft() {
        requireStatus(FormStatus.ON_REVIEW, "Вернуть на доработку можно только форму на согласовании");
        status = FormStatus.DRAFT;
    }

    /** Публикация фиксирует текст и его контрольную сумму: дальше версия неизменяема (FR-1.5, FR-1.6). */
    public void publish(Instant moment, String renderedText, String checksum) {
        requireStatus(FormStatus.APPROVED, "Публикуется только одобренная форма");
        status = FormStatus.PUBLISHED;
        publishedAt = moment;
        validFrom = moment;
        this.renderedText = renderedText;
        renderedChecksum = checksum;
    }

    public void archive(Instant moment) {
        if (status != FormStatus.PUBLISHED && status != FormStatus.APPROVED) {
            throw new IllegalStateException("В архив отправляется опубликованная или одобренная форма");
        }
        status = FormStatus.ARCHIVED;
        validTo = moment;
    }

    /** Действует ли версия в указанный момент (FR-2.3). */
    public boolean isEffectiveAt(Instant moment) {
        return validFrom != null && !validFrom.isAfter(moment) && (validTo == null || moment.isBefore(validTo));
    }

    /**
     * Черновик следующей версии со скопированными пунктами (FR-1.5).
     *
     * @param nextVersionNumber номер следующей версии: считается от старшей версии этого кода, а не от
     *     номера исходной. Отсчёт от исходной ломался на архивной версии — «Новая версия» с v1 при живой
     *     v2 давала снова номер 2 и падала на ограничении уникальности (code, version).
     */
    public ConsentForm newVersion(UUID newId, int nextVersionNumber) {
        if (status != FormStatus.PUBLISHED && status != FormStatus.ARCHIVED) {
            throw new IllegalStateException("Новая версия создаётся от опубликованной или архивной формы");
        }
        ConsentForm next = new ConsentForm(newId, code, nextVersionNumber, title, body);
        next.processingActions = processingActions;
        next.revocationProcedure = revocationProcedure;
        next.sourceChannels = Arrays.copyOf(sourceChannels, sourceChannels.length);
        next.previousVersion = this;
        next.items =
                new ArrayList<>(items.stream().map(item -> item.copyTo(next)).toList());
        return next;
    }

    private void requireStatus(FormStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message + " (текущий статус: " + status.nameRu() + ")");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getTitle() {
        return title;
    }

    public FormStatus getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }

    public String getProcessingActions() {
        return processingActions;
    }

    public String getRevocationProcedure() {
        return revocationProcedure;
    }

    public Set<ConsentSource> getSourceChannels() {
        return Arrays.stream(sourceChannels == null ? new String[0] : sourceChannels)
                .map(ConsentSource::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public String getRenderedText() {
        return renderedText;
    }

    public String getRenderedChecksum() {
        return renderedChecksum;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public ConsentForm getPreviousVersion() {
        return previousVersion;
    }

    public List<ConsentFormItem> getItems() {
        return List.copyOf(items);
    }
}
