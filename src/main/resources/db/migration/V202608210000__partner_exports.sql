-- Stage 4 (§13): учёт выгрузок «Данные для партнёров» (FR-7.4).
--
-- Каждая выгрузка ПДн наружу должна быть объяснима: кто, когда, кому, по какому фильтру и что именно ушло.
-- Таблица append-only по той же причине, что audit_event: она и есть доказательство (§6, FR-10.2).

CREATE TABLE partner_export_log (
    id             UUID         NOT NULL,
    third_party_id UUID         NOT NULL,
    requested_by   VARCHAR(128),
    requested_at   TIMESTAMPTZ  NOT NULL,
    format         VARCHAR(16)  NOT NULL,
    filter         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    records_count  INTEGER      NOT NULL DEFAULT 0,
    -- SHA-256 содержимого файла: позволяет доказать, что партнёру ушёл именно этот набор
    file_checksum  VARCHAR(71),
    content        TEXT,
    expires_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT partner_export_log_pkey PRIMARY KEY (id),
    CONSTRAINT partner_export_log_third_party_fk FOREIGN KEY (third_party_id) REFERENCES third_party (id)
);

CREATE INDEX partner_export_log_third_party_idx ON partner_export_log (third_party_id, requested_at DESC);
CREATE INDEX partner_export_log_expires_idx ON partner_export_log (expires_at);

COMMENT ON COLUMN partner_export_log.content IS 'Тело выгрузки; удаляется по истечении cus.export.ttl (FR-7.4)';

CREATE TRIGGER partner_export_log_append_only
    BEFORE DELETE ON partner_export_log
    FOR EACH STATEMENT EXECUTE FUNCTION cus_reject_journal_modification();
