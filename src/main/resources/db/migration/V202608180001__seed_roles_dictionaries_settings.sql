-- Stage 1 seed (§13): roles of Приложение E, personal data categories, consent types of Приложение B
-- and the operator settings keys of FR-11.3.
--
-- Identifiers are fixed so that every installation, test database and demo dump refers to the same rows.

-- ---------------------------------------------------------------------------------------------------
-- Roles (Приложение E)
-- ---------------------------------------------------------------------------------------------------

INSERT INTO app_role (id, code, name_ru, description) VALUES
    ('00000000-0000-4000-8000-000000000001', 'ADMIN',       'Администратор',         'Полный доступ, управление пользователями и настройками'),
    ('00000000-0000-4000-8000-000000000002', 'DPO',         'Ответственный за ПДн',  'Публикация форм, выгрузки, настройки, чтение аудита'),
    ('00000000-0000-4000-8000-000000000003', 'LAWYER',      'Юрист',                 'Создание, правка и одобрение форм согласий'),
    ('00000000-0000-4000-8000-000000000004', 'MANAGER',     'Менеджер',              'Карточка клиента, отзыв согласия по обращению'),
    ('00000000-0000-4000-8000-000000000005', 'MARKETING',   'Маркетинг',             'Массовая проверка каналов, маскированные контакты'),
    ('00000000-0000-4000-8000-000000000006', 'INTEGRATION', 'Интеграция',            'Сервисная роль: регистрация согласий и выгрузки'),
    ('00000000-0000-4000-8000-000000000007', 'AUDITOR',     'Аудитор',               'Полное чтение журналов аудита и доступа к ПДн');

-- ---------------------------------------------------------------------------------------------------
-- Personal data categories (§6). The list is a working baseline; the operator extends it through
-- the dictionary API without a migration.
-- ---------------------------------------------------------------------------------------------------

INSERT INTO pdn_category (id, code, name_ru, is_special, is_biometric, sort_order) VALUES
    ('00000000-0000-4000-8001-000000000001', 'FIO',            'Фамилия, имя, отчество',        FALSE, FALSE, 10),
    ('00000000-0000-4000-8001-000000000002', 'PHONE',          'Номер телефона',                FALSE, FALSE, 20),
    ('00000000-0000-4000-8001-000000000003', 'EMAIL',          'Адрес электронной почты',       FALSE, FALSE, 30),
    ('00000000-0000-4000-8001-000000000004', 'POSTAL_ADDRESS', 'Почтовый адрес',                FALSE, FALSE, 40),
    ('00000000-0000-4000-8001-000000000005', 'BIRTH_DATE',     'Дата рождения',                 FALSE, FALSE, 50),
    ('00000000-0000-4000-8001-000000000006', 'PASSPORT',       'Паспортные данные',             FALSE, FALSE, 60),
    ('00000000-0000-4000-8001-000000000007', 'SNILS',          'СНИЛС',                         FALSE, FALSE, 70),
    ('00000000-0000-4000-8001-000000000008', 'INN',            'ИНН',                           FALSE, FALSE, 80),
    ('00000000-0000-4000-8001-000000000009', 'EMPLOYMENT',     'Сведения о занятости',          FALSE, FALSE, 90),
    ('00000000-0000-4000-8001-00000000000a', 'INCOME',         'Сведения о доходах',            FALSE, FALSE, 100),
    ('00000000-0000-4000-8001-00000000000b', 'PHOTO',          'Фотография',                    FALSE, TRUE,  110),
    ('00000000-0000-4000-8001-00000000000c', 'HEALTH',         'Сведения о состоянии здоровья', TRUE,  FALSE, 120);

-- ---------------------------------------------------------------------------------------------------
-- Consent types (Приложение B). Validity periods are a placeholder until the customer answers
-- open question 7; they are editable through the API without a migration.
-- ---------------------------------------------------------------------------------------------------

INSERT INTO consent_type (id, code, name_ru, description, category, channels, requires_third_party,
                          default_validity, depends_on_type_id, business_significant, sort_order,
                          created_at, created_by, updated_at, updated_by)
VALUES
    ('00000000-0000-4000-8002-000000000001', 'PDN_PROCESSING',
     'Обработка персональных данных',
     'Базовое согласие на обработку ПДн. Без него запрещены все каналы и передачи (FR-6.2).',
     'PROCESSING', '{}', FALSE, NULL, NULL, TRUE, 10,
     TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),

    ('00000000-0000-4000-8002-000000000002', 'ADVERTISING_PHONE',
     'Реклама по телефону (звонки)', NULL,
     'ADVERTISING', '{PHONE_CALL}', FALSE, NULL,
     '00000000-0000-4000-8002-000000000001', TRUE, 20,
     TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),

    ('00000000-0000-4000-8002-000000000003', 'ADVERTISING_SMS',
     'Реклама по SMS', NULL,
     'ADVERTISING', '{SMS}', FALSE, NULL,
     '00000000-0000-4000-8002-000000000001', TRUE, 30,
     TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),

    ('00000000-0000-4000-8002-000000000004', 'ADVERTISING_EMAIL',
     'Реклама по email', NULL,
     'ADVERTISING', '{EMAIL}', FALSE, NULL,
     '00000000-0000-4000-8002-000000000001', TRUE, 40,
     TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),

    ('00000000-0000-4000-8002-000000000005', 'ADVERTISING_PUSH',
     'Реклама push-уведомлениями', NULL,
     'ADVERTISING', '{PUSH}', FALSE, NULL,
     '00000000-0000-4000-8002-000000000001', TRUE, 50,
     TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),

    ('00000000-0000-4000-8002-000000000006', 'ADVERTISING_MESSENGER',
     'Реклама в мессенджерах', NULL,
     'ADVERTISING', '{MESSENGER}', FALSE, NULL,
     '00000000-0000-4000-8002-000000000001', TRUE, 60,
     TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),

    ('00000000-0000-4000-8002-000000000007', 'ADVERTISING_POSTAL',
     'Рекламная почтовая рассылка', NULL,
     'ADVERTISING', '{POSTAL_MAIL}', FALSE, NULL,
     '00000000-0000-4000-8002-000000000001', TRUE, 70,
     TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),

    ('00000000-0000-4000-8002-000000000008', 'PDN_TRANSFER',
     'Передача ПДн третьему лицу',
     'Требует указания третьего лица из справочника и действующего договора с ним (FR-4.2).',
     'TRANSFER', '{}', TRUE, 'P1Y',
     '00000000-0000-4000-8002-000000000001', TRUE, 80,
     TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),

    ('00000000-0000-4000-8002-000000000009', 'PDN_DISTRIBUTION',
     'Обработка ПДн, разрешённых для распространения',
     'Оформляется отдельной формой: пункт этого типа может быть единственным в форме (ст. 10.1, FR-1.4).',
     'DISTRIBUTION', '{}', FALSE, NULL,
     '00000000-0000-4000-8002-000000000001', TRUE, 90,
     TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),

    ('00000000-0000-4000-8002-00000000000a', 'LOYALTY_PROGRAM',
     'Участие в программе лояльности', NULL,
     'OTHER', '{}', FALSE, NULL,
     '00000000-0000-4000-8002-000000000001', FALSE, 100,
     TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system', TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system');

-- ---------------------------------------------------------------------------------------------------
-- Operator settings (FR-11.3). Values marked «не заполнено» must be filled in before the first form is
-- published: FR-1.3 validates that the name and the address of the operator are present. Открытый вопрос 11.
-- ---------------------------------------------------------------------------------------------------

INSERT INTO operator_settings (key, value, updated_at, updated_by) VALUES
    ('operator.name',                    'не заполнено',   TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('operator.address',                 'не заполнено',   TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('operator.inn',                     'не заполнено',   TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('operator.ogrn',                    'не заполнено',   TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('dpo.name',                         'не заполнено',   TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('dpo.email',                        'не заполнено',   TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('dpo.phone',                        'не заполнено',   TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('inconsensu.timezone',                     'Europe/Moscow',  TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('inconsensu.status.expiring-days',         '30',             TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('inconsensu.notification.thresholds',      '30,15,7,1',      TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('inconsensu.notification.digest-threshold','20',             TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('inconsensu.export.ttl',                   'PT24H',          TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('inconsensu.approval.required-roles',      'LAWYER,DPO',     TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('inconsensu.revocation.cascade-enabled',   'true',           TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('inconsensu.selfservice.auth-mode',        'SERVICE_TOKEN',  TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('branding.primary-color',           '#0d6efd',        TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system'),
    ('branding.logo-url',                '',               TIMESTAMPTZ '2026-08-18 00:00:00+00', 'system');
