-- Этап 8 (§13, NFR-5): архивные таблицы и журнал прогонов ретенции.
--
-- Согласия и журналы не удаляются, пока идёт обработка и не истёк срок исковой давности. Архивация — это
-- перенос «холодных» записей в отдельные таблицы: рабочие выборки перестают их читать, а доказательство
-- сохраняется. Партиционирование по годам добавляется отдельной миграцией, когда объёмы этого потребуют.

CREATE TABLE consent_archive (
    LIKE consent INCLUDING DEFAULTS INCLUDING CONSTRAINTS
);

ALTER TABLE consent_archive ADD COLUMN archived_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX consent_archive_subject_idx ON consent_archive (subject_id);
CREATE INDEX consent_archive_archived_idx ON consent_archive (archived_at);

-- Журнал аудита append-only на уровне БД (FR-10.2), и это сознательно: автоматическая задача не имеет
-- права его чистить. Старые события считаются и попадают в отчёт, а перенос выполняет администратор
-- отдельной ролью по процедуре из docs/runbook.md.

CREATE TABLE retention_run (
    id                 UUID         NOT NULL,
    started_at         TIMESTAMPTZ  NOT NULL,
    finished_at        TIMESTAMPTZ,
    dry_run            BOOLEAN      NOT NULL,
    consents_archived  BIGINT       NOT NULL DEFAULT 0,
    -- Сколько событий журнала перешагнуло срок хранения: чистит их администратор вручную (FR-10.2)
    events_aged        BIGINT       NOT NULL DEFAULT 0,
    exports_purged     BIGINT       NOT NULL DEFAULT 0,
    notifications_purged BIGINT     NOT NULL DEFAULT 0,
    started_by         VARCHAR(128),
    error              TEXT,
    CONSTRAINT retention_run_pkey PRIMARY KEY (id)
);

CREATE INDEX retention_run_started_idx ON retention_run (started_at DESC);

-- Настройки политики хранения (NFR-5). Значения по умолчанию соответствуют общему сроку исковой давности
-- три года после прекращения обработки; фактические сроки заказчик уточняет (вопрос 7).
INSERT INTO operator_settings (key, value, updated_at, updated_by) VALUES
    ('cus.retention.consents-after-revocation', 'P3Y',  TIMESTAMPTZ '2026-08-24 00:00:00+00', 'system'),
    ('cus.retention.audit-events',              'P5Y',  TIMESTAMPTZ '2026-08-24 00:00:00+00', 'system'),
    ('cus.retention.partner-exports',           'P30D', TIMESTAMPTZ '2026-08-24 00:00:00+00', 'system'),
    ('cus.retention.enabled',                   'false',TIMESTAMPTZ '2026-08-24 00:00:00+00', 'system');
