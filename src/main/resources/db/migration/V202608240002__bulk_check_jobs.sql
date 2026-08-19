-- Этап 8 (§13): асинхронная массовая проверка каналов.
--
-- Синхронный вызов ограничен 10 000 идентификаторами (FR-6.4). Рассылке нужен ответ по всей базе, и
-- держать ради этого HTTP-соединение бессмысленно: задача считается в фоне, клиент забирает результат.

CREATE TABLE bulk_check_job (
    id            UUID         NOT NULL,
    channel       VARCHAR(32)  NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    requested     INTEGER      NOT NULL DEFAULT 0,
    processed     INTEGER      NOT NULL DEFAULT 0,
    allowed_count INTEGER      NOT NULL DEFAULT 0,
    identifiers   JSONB        NOT NULL,
    result        JSONB,
    started_by    VARCHAR(128),
    started_at    TIMESTAMPTZ  NOT NULL,
    finished_at   TIMESTAMPTZ,
    error         TEXT,
    CONSTRAINT bulk_check_job_pkey PRIMARY KEY (id)
);

CREATE INDEX bulk_check_job_started_idx ON bulk_check_job (started_at DESC);
