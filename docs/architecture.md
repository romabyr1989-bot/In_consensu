# Архитектура In consensu

Документ соответствует NFR-7 и описывает систему на уровнях C4 1–2. Детальные требования — в
[`TZ.md`](TZ.md), принятые решения — в [`DECISIONS.md`](DECISIONS.md).

## 1. Контекст (C4 уровень 1)

In consensu — внутренняя система оператора персональных данных. Она не рассылает сообщения и не хранит
клиентскую базу: она отвечает на вопрос «можно ли», доказывает наличие согласия и уведомляет
ответственных.

```mermaid
flowchart TB
  subgraph Люди
    Manager["Сотрудник<br/>колл-центр, офис, маркетинг"]
    Lawyer["Юрист и DPO"]
    Auditor["Аудитор"]
    Client["Субъект ПДн (клиент)"]
  end

  CUS["<b>In consensu</b><br/>Центр управления согласиями<br/>Java 21, Spring Boot, PostgreSQL"]

  subgraph Системы оператора
    CRM["CRM / мастер-система клиентов"]
    Campaign["Системы рассылок"]
    LK["Личный кабинет и<br/>мобильное приложение"]
    DocStore["Хранилище сканов<br/>(DocumentStorage)"]
    IdP["Корпоративный IdP<br/>(профиль oidc)"]
    SMTP["SMTP-сервер"]
  end

  Partner["Третьи лица и компании экосистемы"]

  Manager -->|веб-интерфейс §16| CUS
  Lawyer -->|конструктор и согласование форм| CUS
  Auditor -->|журналы и проверка целостности| CUS
  Client -->|страница самообслуживания UI-18| CUS

  CRM -->|регистрация согласий, импорт базы| CUS
  LK -->|self-service API: просмотр и отзыв| CUS
  Campaign -->|проверка каналов, массовые проверки| CUS
  CUS -->|webhooks: consent.granted / revoked / expiring| Campaign
  CUS -->|письма о переподписании| SMTP
  CUS -->|ссылки на сканы и метаданные| DocStore
  CUS -->|проверка токенов| IdP
  CUS -->|выгрузки «Данные для партнёров»| Partner
```

Границы работ — §3 ТЗ. Вне объёма: сами рассылки, CRM, мобильное приложение, УКЭП (интерфейс
`SignatureProvider` с заглушкой), хранилище сканов (интерфейс `DocumentStorage`), юридический аудит
текстов.

## 2. Контейнеры (C4 уровень 2)

```mermaid
flowchart LR
  Client["Клиенты API и UI"]

  subgraph Deploy["Контур оператора (РФ, NFR-4)"]
    App["<b>cus</b><br/>Spring Boot, модульный монолит<br/>один деплой-юнит, stateless"]
    DB[("PostgreSQL 16 / Postgres Pro<br/>согласия, каталог, журналы, outbox")]
    Mail["SMTP / Mailpit в dev"]
  end

  Consumer["Внешние потребители событий<br/>(webhook-подписки)"]
  Prom["Prometheus"]

  Client -->|HTTPS, JWT / сессия| App
  App -->|JDBC| DB
  App -->|SMTP| Mail
  App -->|HTTPS + HMAC-SHA256| Consumer
  Prom -->|/actuator/prometheus| App
```

Приложение горизонтально масштабируется (NFR-2): состояние живёт в PostgreSQL, фоновые задачи
защищены ShedLock, внешние эффекты идут только через транзакционный outbox.

## 3. Модули (C4 уровень 3, обзор)

Модульный монолит: один Maven-модуль, границы модулей — пакеты, правила проверяются ArchUnit (§5).

```mermaid
flowchart TB
  ui["ui<br/>Thymeleaf + HTMX"]
  integration["integration<br/>импорт, экспорт, self-service"]
  catalog["catalog<br/>типы, формы, согласование"]
  registry["registry<br/>субъекты, согласия, отзыв"]
  channels["channels<br/>разрешённые каналы"]
  thirdparty["thirdparty<br/>третьи лица, передачи"]
  notification["notification<br/>правила, outbox, доставка"]
  audit["audit<br/>журнал с хеш-цепочкой"]
  iam["iam<br/>пользователи и роли"]
  common["common<br/>ошибки, время, конфигурация"]

  ui --> catalog & registry & channels & thirdparty & notification & audit & iam
  integration --> registry & catalog
  registry --> catalog & thirdparty
  channels --> registry & catalog
  notification --> registry & thirdparty
  registry -.события.-> audit
  catalog -.события.-> audit
  registry -.события.-> notification
  catalog & registry & thirdparty & channels & notification & integration & audit & iam --> common
```

Правила (закреплены ArchUnit-тестами):

- слои внутри модуля: `api` → `application` → `domain`, инфраструктура подключается снаружи;
  `domain` не зависит от `api` и `infrastructure`;
- модули общаются только через application-сервисы и Spring `ApplicationEvent`;
- прямой доступ к репозиториям чужого модуля запрещён;
- DTO не используются как JPA-сущности; вывод только через slf4j.

## 4. Сквозные решения

| Тема | Решение | Где |
|---|---|---|
| Ошибки | RFC 9457 `ProblemDetail`, `type = urn:inconsensu:error:<code>`, поле `errors[]` | `common/error` |
| Корреляция | заголовок `X-Request-Id`, тот же идентификатор в MDC, логах и теле ошибки | `common/web` |
| Логи | JSON (structured logging Spring Boot), без ПДн (NFR-3) | `application.yml` |
| Время | в БД UTC, в API ISO-8601 с зоной, бизнес-даты в таймзоне оператора | `common/config` |
| Схема БД | Flyway, чистый SQL, `ddl-auto=validate`, миграции неизменяемы | `db/migration` |
| Фоновые задачи | `@Scheduled` + ShedLock (таблица в PostgreSQL) | этапы 3, 6 |
| Внешние эффекты | только через транзакционный outbox в одной транзакции с изменением данных | `notification/application` |
| Подпись событий | HMAC-SHA256 тела на секрете подписки, заголовок `X-InConsensu-Signature` | [`webhooks.md`](webhooks.md) |
| Шифрование контактов | AES-256-GCM под флагом `inconsensu.crypto.enabled`, поиск по HMAC | `common/application`, [`runbook.md`](runbook.md) |
| Хранение | архивация отозванных согласий, журналы остаются append-only | [`runbook.md`](runbook.md) |
| Фоновые запуски | задача ставится после коммита транзакции, а не внутри неё | `common/application/AfterCommitExecutor` |
| Наблюдаемость | Actuator health / prometheus, Micrometer | `common/config` |

### Доставка событий наружу (§8.6, FR-9.3)

```mermaid
sequenceDiagram
  participant S as Сервис домена
  participant DB as PostgreSQL
  participant P as OutboxProcessor
  participant C as Потребитель

  S->>DB: изменение данных + запись в outbox (одна транзакция)
  Note over S,DB: слушатель события работает с propagation=MANDATORY:<br/>событие не может уйти по откатившейся транзакции
  P->>DB: выбрать готовые события (по одному агрегату — строго по порядку)
  P->>C: POST с X-InConsensu-Event, X-InConsensu-Delivery-Id, X-InConsensu-Signature
  alt 2xx
    P->>DB: SENT + запись в журнал доставок
  else ошибка
    P->>DB: RETRY по расписанию 1м / 5м / 30м / 2ч / 12ч
    Note over P,DB: после исчерпания — FAILED и письмо администратору
  end
```

Тело события формируется один раз, при записи в outbox: повтор обязан отправить тот же JSON, иначе
перестанут работать подпись и дедупликация у потребителя (ADR-0029). Персональных данных в теле нет —
только внешний идентификатор субъекта (NFR-3).

## 5. Потоки данных

Соответствует схеме продукта (§5 ТЗ):

```mermaid
flowchart LR
  subgraph CUS["In consensu"]
    C[Конструктор согласий] --> A[Согласование: юрист, DPO]
    A --> K[Каталог согласий и форм]
    K --> S[Подписание / регистрация согласия]
    S --> B[(База согласий)]
    B --> Card[Карточка клиента]
    B --> Ch[Разрешённые каналы]
    B --> T[Передачи третьим лицам]
    B --> N[Уведомления: email / webhook]
  end
  Src[Источники: договор, доп. соглашение, ЛК, сайт, лояльность, база клиентов] --> S
  Ch --> M[Рассылки, CRM, колл-центр]
  T --> P[Данные для партнёров]
  LK[ЛК / мобильное приложение] -->|отзыв| B
```

## 6. Развёртывание

Один контейнер приложения + PostgreSQL. Поддерживаются Docker и Podman, Alt Linux (Альт Сервер),
PostgreSQL 15/16 и Postgres Pro Standard, OpenJDK 21 (Axiom JDK, Liberica). TLS терминируется на
периметре. Подробности эксплуатации — в `runbook.md` (этап 8).
