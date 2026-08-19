-- Этап 7 (§13, UI-18): одноразовые ссылки на страницу самообслуживания.
--
-- Таблица, а не память экземпляра: ссылку выдаёт один экземпляр, а открывают её на другом, и «ссылка
-- недействительна» на балансировщике выглядело бы как поломка. Хранится только хеш токена — сам токен
-- существует лишь в ссылке, которую ЦУС отдал личному кабинету (NFR-3).

CREATE TABLE self_ui_session (
    id                 UUID         NOT NULL,
    token_hash         VARCHAR(64)  NOT NULL,
    subject_id         UUID         NOT NULL,
    issued_by          VARCHAR(128),
    issued_at          TIMESTAMPTZ  NOT NULL,
    link_expires_at    TIMESTAMPTZ  NOT NULL,
    used_at            TIMESTAMPTZ,
    session_expires_at TIMESTAMPTZ,
    CONSTRAINT self_ui_session_pkey PRIMARY KEY (id),
    CONSTRAINT self_ui_session_token_uk UNIQUE (token_hash),
    CONSTRAINT self_ui_session_subject_fk FOREIGN KEY (subject_id) REFERENCES subject (id)
);

CREATE INDEX self_ui_session_expiry_idx ON self_ui_session (link_expires_at);
