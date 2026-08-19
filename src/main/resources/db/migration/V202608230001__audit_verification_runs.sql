-- Этап 7 (§13, FR-10.4, UI-15): история проверок целостности журнала.
--
-- Таблица, а не память: приложение stateless и масштабируется горизонтально (NFR-2), а проверку
-- запускают на одном экземпляре, читают отчёт — на другом.

CREATE TABLE audit_verification (
    id                  UUID         NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    started_by          VARCHAR(128),
    started_at          TIMESTAMPTZ  NOT NULL,
    finished_at         TIMESTAMPTZ,
    integrity           VARCHAR(16),
    aggregates_checked  BIGINT       NOT NULL DEFAULT 0,
    events_checked      BIGINT       NOT NULL DEFAULT 0,
    anchors_checked     BIGINT       NOT NULL DEFAULT 0,
    problems            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    error               TEXT,
    CONSTRAINT audit_verification_pkey PRIMARY KEY (id)
);

CREATE INDEX audit_verification_started_idx ON audit_verification (started_at DESC);
