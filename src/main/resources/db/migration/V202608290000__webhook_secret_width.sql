-- §6: webhook_subscription.secret шифруется в БД. Под шифртекст с префиксом enc:v1: нужно больше места,
-- чем под открытое значение, — как это уже сделано для контактов субъекта (V202608250000).
--
-- Значения не переписываются: конвертер отдаёт строку без префикса как есть, поэтому уже заведённые
-- подписки продолжают работать и получают шифрование при первом же сохранении.

ALTER TABLE webhook_subscription ALTER COLUMN secret TYPE VARCHAR(2048);

COMMENT ON COLUMN webhook_subscription.secret IS 'Секрет подписи HMAC; при inconsensu.crypto.enabled — шифртекст с префиксом enc:v1: (§6, NFR-3)';
