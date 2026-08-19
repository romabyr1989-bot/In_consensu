-- Переименование продукта в «In consensu»: комментарии схемы приводятся к новым именам.
--
-- Прежние миграции неизменяемы (§14.5), поэтому тексты, которые видит администратор базы, правятся
-- отдельным шагом, а не переписыванием применённых файлов. Схема не меняется — только описания.

COMMENT ON TABLE subject IS 'In consensu не мастер-система по клиентам: хранится минимально необходимый состав (§6)';

COMMENT ON COLUMN partner_export_log.content IS 'Тело выгрузки; удаляется по истечении inconsensu.export.ttl (FR-7.4)';

COMMENT ON COLUMN subject_contact.search_hmac IS 'HMAC-SHA256 нормализованного значения для поиска при inconsensu.crypto.enabled (NFR-3)';

COMMENT ON COLUMN subject_contact.value IS 'Контакт; при inconsensu.crypto.enabled — шифртекст с префиксом enc:v1: (NFR-3)';
