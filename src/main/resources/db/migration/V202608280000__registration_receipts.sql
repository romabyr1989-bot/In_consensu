-- FR-4.1: повторный запрос с тем же Idempotency-Key обязан вернуть исходный результат.
--
-- Раньше ключ жил только внутри созданных согласий, поэтому запрос, все пункты которого отклонены,
-- не оставлял следа: повтор выполнялся заново, отвечал 201 вместо 200 и писал вторые события DECLINED.
-- Квитанция фиксирует сам факт обработки ключа и состав отклонённых пунктов, чтобы повтор отвечал тем же.

CREATE TABLE registration_receipt (
    id                 UUID PRIMARY KEY,
    idempotency_key    TEXT        NOT NULL UNIQUE,
    subject_id         UUID        NOT NULL REFERENCES subject (id),
    declined_item_ids  UUID[]      NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE registration_receipt IS 'Обработанные запросы регистрации: идемпотентность независимо от числа созданных согласий (FR-4.1)';
COMMENT ON COLUMN registration_receipt.declined_item_ids IS 'Пункты с accepted = false: повтор обязан вернуть тот же состав';
