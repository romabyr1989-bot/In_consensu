-- Stage 2 (§13): конструктор форм согласий и их согласование.
--
-- Опубликованная версия формы неизменяема (FR-1.5): любое изменение — новая версия с version + 1 и ссылкой
-- previous_version_id. Согласия, выданные по архивной версии, остаются действующими, поэтому строки старых
-- версий никогда не удаляются.

CREATE TABLE consent_form (
    id                    UUID         NOT NULL,
    code                  VARCHAR(64)  NOT NULL,
    version               INTEGER      NOT NULL,
    title                 VARCHAR(512) NOT NULL,
    status                VARCHAR(32)  NOT NULL,
    -- Markdown с плейсхолдерами {{operator.name}}, {{subject.fio}} и т. д. (FR-1.2)
    body                  TEXT         NOT NULL,
    processing_actions    TEXT,
    revocation_procedure  TEXT,
    source_channels       TEXT[]       NOT NULL DEFAULT '{}',
    valid_from            TIMESTAMPTZ,
    valid_to              TIMESTAMPTZ,
    -- SHA-256 канонического рендера без данных субъекта, вычисляется при публикации (FR-1.6)
    rendered_checksum     VARCHAR(71),
    -- Момент последней отправки на согласование: одобрения прошлого круга не засчитываются после
    -- возврата на доработку, а история решений при этом сохраняется целиком (FR-2.1, FR-2.2).
    submitted_at          TIMESTAMPTZ,
    published_at          TIMESTAMPTZ,
    previous_version_id   UUID,
    created_at            TIMESTAMPTZ  NOT NULL,
    created_by            VARCHAR(128),
    updated_at            TIMESTAMPTZ  NOT NULL,
    updated_by            VARCHAR(128),
    version_lock          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT consent_form_pkey PRIMARY KEY (id),
    CONSTRAINT consent_form_code_version_uk UNIQUE (code, version),
    CONSTRAINT consent_form_previous_fk FOREIGN KEY (previous_version_id) REFERENCES consent_form (id)
);

COMMENT ON COLUMN consent_form.version_lock IS 'Оптимистичная блокировка (§6); имя отличается от version, которое хранит номер версии формы';

CREATE INDEX consent_form_status_idx ON consent_form (status, code, version);
CREATE INDEX consent_form_code_idx ON consent_form (code, version DESC);

CREATE TABLE consent_form_item (
    id              UUID    NOT NULL,
    form_id         UUID    NOT NULL,
    consent_type_id UUID    NOT NULL,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    text            TEXT    NOT NULL,
    purposes        TEXT[]  NOT NULL DEFAULT '{}',
    pdn_categories  TEXT[]  NOT NULL DEFAULT '{}',
    third_party_id  UUID,
    -- ISO-8601 duration; NULL — срок берётся из типа согласия (FR-4.3)
    validity        VARCHAR(32),
    is_mandatory    BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT consent_form_item_pkey PRIMARY KEY (id),
    CONSTRAINT consent_form_item_form_fk FOREIGN KEY (form_id) REFERENCES consent_form (id) ON DELETE CASCADE,
    CONSTRAINT consent_form_item_type_fk FOREIGN KEY (consent_type_id) REFERENCES consent_type (id),
    CONSTRAINT consent_form_item_third_party_fk FOREIGN KEY (third_party_id) REFERENCES third_party (id)
);

COMMENT ON TABLE consent_form_item IS 'Пункт формы: согласие даётся только активным действием, поля «предотмеченный чекбокс» нет (§6)';

CREATE INDEX consent_form_item_form_idx ON consent_form_item (form_id, sort_order);
CREATE INDEX consent_form_item_type_idx ON consent_form_item (consent_type_id);

CREATE TABLE form_approval (
    id            UUID         NOT NULL,
    form_id       UUID         NOT NULL,
    role_required VARCHAR(32)  NOT NULL,
    user_id       UUID,
    user_login    VARCHAR(128),
    decision      VARCHAR(32)  NOT NULL,
    comment       TEXT,
    decided_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT form_approval_pkey PRIMARY KEY (id),
    CONSTRAINT form_approval_form_fk FOREIGN KEY (form_id) REFERENCES consent_form (id) ON DELETE CASCADE
);

COMMENT ON TABLE form_approval IS 'История согласования формы: кто, когда и с каким комментарием решил (FR-2.2)';

CREATE INDEX form_approval_form_idx ON form_approval (form_id, decided_at);
