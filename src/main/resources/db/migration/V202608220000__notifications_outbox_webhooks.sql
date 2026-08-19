-- Stage 6 (§13): уведомления о переподписании, транзакционный outbox и webhooks.
--
-- Ключевое требование §8.6: любой внешний эффект (письмо, webhook) записывается в outbox в одной транзакции
-- с изменением данных. Иначе согласие окажется отозванным, а системы-потребители об этом не узнают —
-- либо наоборот, событие уйдёт по откатившейся транзакции.

CREATE TABLE outbox_event (
    id              UUID         NOT NULL,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(128) NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    processed_at    TIMESTAMPTZ,
    last_error      TEXT,
    CONSTRAINT outbox_event_pkey PRIMARY KEY (id)
);

-- Выборка очереди: только неотправленные, в порядке появления. Порядок событий по одному согласию
-- сохраняется за счёт сортировки по created_at и id (FR-9.3).
CREATE INDEX outbox_event_pending_idx ON outbox_event (status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX outbox_event_aggregate_idx ON outbox_event (aggregate_type, aggregate_id, created_at);

CREATE TABLE webhook_subscription (
    id          UUID         NOT NULL,
    name        VARCHAR(255) NOT NULL,
    url         VARCHAR(1024) NOT NULL,
    -- Секрет подписи HMAC. Шифрование на уровне приложения — этап 8 (NFR-3, флаг inconsensu.crypto.enabled)
    secret      VARCHAR(255) NOT NULL,
    event_types TEXT[]       NOT NULL DEFAULT '{}',
    headers     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(128),
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(128),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT webhook_subscription_pkey PRIMARY KEY (id)
);

CREATE TABLE webhook_delivery (
    id              UUID         NOT NULL,
    subscription_id UUID         NOT NULL,
    outbox_event_id UUID         NOT NULL,
    attempt         INTEGER      NOT NULL,
    response_code   INTEGER,
    error           TEXT,
    delivered_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT webhook_delivery_pkey PRIMARY KEY (id),
    CONSTRAINT webhook_delivery_subscription_fk FOREIGN KEY (subscription_id) REFERENCES webhook_subscription (id) ON DELETE CASCADE
);

-- Внешнего ключа на outbox_event намеренно нет: тестовая отправка (FR-9.5) — это попытка доставки без
-- события, а журнал доставок обязан хранить и её.

CREATE INDEX webhook_delivery_subscription_idx ON webhook_delivery (subscription_id, delivered_at DESC);
CREATE INDEX webhook_delivery_event_idx ON webhook_delivery (outbox_event_id);

CREATE TABLE notification_rule (
    id               UUID         NOT NULL,
    name             VARCHAR(255) NOT NULL,
    trigger_type     VARCHAR(64)  NOT NULL,
    -- Пороги в днях до окончания срока: по умолчанию {30, 15, 7, 1} (§6)
    days_before      INTEGER[]    NOT NULL DEFAULT '{}',
    consent_type_id  UUID,
    third_party_id   UUID,
    recipient_emails TEXT[]       NOT NULL DEFAULT '{}',
    recipient_roles  TEXT[]       NOT NULL DEFAULT '{}',
    channels         TEXT[]       NOT NULL DEFAULT '{}',
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(128),
    updated_at       TIMESTAMPTZ  NOT NULL,
    updated_by       VARCHAR(128),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT notification_rule_pkey PRIMARY KEY (id),
    CONSTRAINT notification_rule_type_fk FOREIGN KEY (consent_type_id) REFERENCES consent_type (id),
    CONSTRAINT notification_rule_third_party_fk FOREIGN KEY (third_party_id) REFERENCES third_party (id)
);

CREATE INDEX notification_rule_active_idx ON notification_rule (is_active, trigger_type);

CREATE TABLE notification (
    id          UUID         NOT NULL,
    rule_id     UUID,
    consent_id  UUID,
    subject_id  UUID,
    -- Дедупликация: один порог по одному согласию — одно уведомление (FR-9.1)
    dedupe_key  VARCHAR(255) NOT NULL,
    channel     VARCHAR(32)  NOT NULL,
    recipient   VARCHAR(512) NOT NULL,
    subject_line VARCHAR(512),
    body        TEXT,
    -- Строка для таблицы и CSV дайджеста: ФИО, external_id, тип, третье лицо, дата окончания (FR-9.2).
    -- Хранится вместе с уведомлением, потому что на момент отправки исходная выборка уже неповторима:
    -- «ровно N дней до окончания» назавтра даст другой список.
    data        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status      VARCHAR(32)  NOT NULL,
    attempts    INTEGER      NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    sent_at     TIMESTAMPTZ,
    CONSTRAINT notification_pkey PRIMARY KEY (id),
    CONSTRAINT notification_dedupe_key_uk UNIQUE (dedupe_key),
    CONSTRAINT notification_rule_fk FOREIGN KEY (rule_id) REFERENCES notification_rule (id) ON DELETE SET NULL,
    CONSTRAINT notification_consent_fk FOREIGN KEY (consent_id) REFERENCES consent (id)
);

CREATE INDEX notification_status_idx ON notification (status, created_at);
CREATE INDEX notification_rule_idx ON notification (rule_id, created_at DESC);

-- Правила по умолчанию: DPO узнаёт о переподписании заранее, а администратор — о сбоях доставки.
INSERT INTO notification_rule (id, name, trigger_type, days_before, recipient_roles, channels, created_at, created_by, updated_at, updated_by)
VALUES
    ('00000000-0000-4000-8003-000000000001', 'Истекающие согласия', 'EXPIRING', '{30,15,7,1}', '{DPO}', '{EMAIL,WEBHOOK}',
     TIMESTAMPTZ '2026-08-22 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-22 00:00:00+00', 'system'),
    ('00000000-0000-4000-8003-000000000002', 'Истёкшие согласия', 'EXPIRED', '{}', '{DPO}', '{EMAIL,WEBHOOK}',
     TIMESTAMPTZ '2026-08-22 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-22 00:00:00+00', 'system'),
    ('00000000-0000-4000-8003-000000000003', 'Истекающие договоры с третьими лицами', 'THIRD_PARTY_CONTRACT_EXPIRING', '{30}', '{DPO}', '{EMAIL}',
     TIMESTAMPTZ '2026-08-22 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-22 00:00:00+00', 'system');
