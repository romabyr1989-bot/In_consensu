-- Stage 3 (§13): экземпляры согласий и асинхронный импорт.
--
-- Согласие — это снимок условий на момент выражения воли (§8.5): контрольная сумма формы, категории ПДн,
-- цели и третье лицо копируются в строку. Справочники потом меняются, а доказательство остаётся прежним.
-- Строки согласий не удаляются и не редактируются задним числом (§6).

CREATE TABLE consent (
    id                 UUID         NOT NULL,
    subject_id         UUID         NOT NULL,
    consent_type_id    UUID         NOT NULL,
    form_id            UUID,
    form_item_id       UUID,
    -- Копия consent_form.rendered_checksum на момент регистрации (FR-1.6)
    form_checksum      VARCHAR(71),
    source             VARCHAR(64)  NOT NULL,
    source_ref         VARCHAR(255),
    -- Материализованный статус для фильтров и отчётов; источник правды — расчёт при чтении (FR-5.3)
    status             VARCHAR(32)  NOT NULL,
    granted_at         TIMESTAMPTZ  NOT NULL,
    valid_until        TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    revocation_reason  TEXT,
    revocation_source  VARCHAR(32),
    third_party_id     UUID,
    -- Снимки на момент регистрации: справочник потом меняется, доказательство — нет (§8.5)
    pdn_categories     TEXT[]       NOT NULL DEFAULT '{}',
    purposes           TEXT[]       NOT NULL DEFAULT '{}',
    signature_type     VARCHAR(32)  NOT NULL,
    evidence           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    superseded_by_id   UUID,
    -- FR-4.1: повторный вызов с тем же ключом возвращает исходный результат
    idempotency_key    VARCHAR(255) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(128),
    updated_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(128),
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT consent_pkey PRIMARY KEY (id),
    CONSTRAINT consent_idempotency_key_uk UNIQUE (idempotency_key),
    CONSTRAINT consent_subject_fk FOREIGN KEY (subject_id) REFERENCES subject (id),
    CONSTRAINT consent_type_fk FOREIGN KEY (consent_type_id) REFERENCES consent_type (id),
    CONSTRAINT consent_form_fk FOREIGN KEY (form_id) REFERENCES consent_form (id),
    CONSTRAINT consent_form_item_fk FOREIGN KEY (form_item_id) REFERENCES consent_form_item (id),
    CONSTRAINT consent_third_party_fk FOREIGN KEY (third_party_id) REFERENCES third_party (id),
    CONSTRAINT consent_superseded_by_fk FOREIGN KEY (superseded_by_id) REFERENCES consent (id)
);

-- Индексы §6: карточка клиента, ежедневная задача статусов и выгрузки партнёрам.
CREATE INDEX consent_subject_type_status_idx ON consent (subject_id, consent_type_id, status);
CREATE INDEX consent_valid_until_idx ON consent (valid_until) WHERE status IN ('ACTIVE', 'EXPIRING');
CREATE INDEX consent_third_party_status_idx ON consent (third_party_id, status) WHERE third_party_id IS NOT NULL;
CREATE INDEX consent_granted_at_idx ON consent (granted_at);

COMMENT ON COLUMN consent.superseded_by_id IS 'Заполняется, когда субъект дал новое согласие того же типа (FR-4.3)';

-- FR-4.5: импорт исторических согласий выполняется асинхронно, с прогрессом и построчным отчётом.
CREATE TABLE import_job (
    id          UUID         NOT NULL,
    source      VARCHAR(64)  NOT NULL,
    file_name   VARCHAR(255),
    dry_run     BOOLEAN      NOT NULL DEFAULT TRUE,
    status      VARCHAR(32)  NOT NULL,
    total       INTEGER      NOT NULL DEFAULT 0,
    imported    INTEGER      NOT NULL DEFAULT 0,
    rejected    INTEGER      NOT NULL DEFAULT 0,
    report      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    started_by  VARCHAR(128),
    started_at  TIMESTAMPTZ  NOT NULL,
    finished_at TIMESTAMPTZ,
    CONSTRAINT import_job_pkey PRIMARY KEY (id)
);

CREATE INDEX import_job_started_at_idx ON import_job (started_at DESC);
