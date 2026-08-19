-- Этап 8 (§13, NFR-3): шифрование контактов на уровне приложения.
--
-- Значение и нормализованное значение остаются в тех же колонках, но при включённом флаге хранят шифртекст
-- AES-256-GCM. Поиск по точному совпадению переезжает на HMAC нормализованного значения: шифртекст с
-- каждым вызовом разный (случайный IV), сравнивать по нему нельзя, а HMAC детерминирован при том же ключе.

ALTER TABLE subject_contact ADD COLUMN search_hmac VARCHAR(64);

CREATE INDEX subject_contact_search_hmac_idx ON subject_contact (type, search_hmac)
    WHERE search_hmac IS NOT NULL;

COMMENT ON COLUMN subject_contact.search_hmac IS 'HMAC-SHA256 нормализованного значения для поиска при cus.crypto.enabled (NFR-3)';
