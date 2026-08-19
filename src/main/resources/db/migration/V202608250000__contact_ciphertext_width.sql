-- Этап 8 (NFR-3): место под шифртекст контакта.
--
-- Значение до 512 символов после AES-256-GCM и base64 занимает примерно вдвое больше, плюс вектор
-- инициализации, тег и префикс. В VARCHAR(512) такой контакт не помещался, и включение шифрования
-- ломало сохранение длинных адресов — колонки расширяются с запасом.

ALTER TABLE subject_contact ALTER COLUMN value TYPE VARCHAR(2048);
ALTER TABLE subject_contact ALTER COLUMN value_normalized TYPE VARCHAR(2048);

COMMENT ON COLUMN subject_contact.value IS 'Контакт; при cus.crypto.enabled — шифртекст с префиксом enc:v1: (NFR-3)';
