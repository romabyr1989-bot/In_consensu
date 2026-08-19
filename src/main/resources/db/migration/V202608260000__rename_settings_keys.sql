-- Переименование продукта в «In consensu»: ключи настроек оператора получают новый префикс.
--
-- Прежние миграции неизменяемы, поэтому ключи переименовываются отдельным шагом. Значения сохраняются:
-- настройки оператора уже могут быть изменены в эксплуатации, и терять их нельзя.

UPDATE operator_settings SET key = 'inconsensu.timezone'                      WHERE key = 'cus.timezone';
UPDATE operator_settings SET key = 'inconsensu.status.expiring-days'          WHERE key = 'cus.status.expiring-days';
UPDATE operator_settings SET key = 'inconsensu.notification.thresholds'       WHERE key = 'cus.notification.thresholds';
UPDATE operator_settings SET key = 'inconsensu.notification.digest-threshold' WHERE key = 'cus.notification.digest-threshold';
UPDATE operator_settings SET key = 'inconsensu.export.ttl'                    WHERE key = 'cus.export.ttl';
UPDATE operator_settings SET key = 'inconsensu.approval.required-roles'       WHERE key = 'cus.approval.required-roles';
UPDATE operator_settings SET key = 'inconsensu.revocation.cascade-enabled'    WHERE key = 'cus.revocation.cascade-enabled';
UPDATE operator_settings SET key = 'inconsensu.selfservice.auth-mode'         WHERE key = 'cus.selfservice.auth-mode';
UPDATE operator_settings SET key = 'inconsensu.retention.consents-after-revocation' WHERE key = 'cus.retention.consents-after-revocation';
UPDATE operator_settings SET key = 'inconsensu.retention.audit-events'        WHERE key = 'cus.retention.audit-events';
UPDATE operator_settings SET key = 'inconsensu.retention.partner-exports'     WHERE key = 'cus.retention.partner-exports';
UPDATE operator_settings SET key = 'inconsensu.retention.enabled'             WHERE key = 'cus.retention.enabled';
