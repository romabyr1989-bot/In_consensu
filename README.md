# In consensu — центр управления согласиями

Серверное приложение для учёта согласий субъектов персональных данных: единый каталог типов и форм
согласий, база выданных согласий, ответ «можно / нельзя» по каналам коммуникации и передачам третьим
лицам, отзыв согласия клиентом, уведомления о переподписании и доказуемый журнал событий.

Нормативная база — 152-ФЗ «О персональных данных» и 38-ФЗ «О рекламе».
Требования и план работ: [`docs/TZ.md`](docs/TZ.md).

> **Текущее состояние: закрыты этапы 0–8 из §13.**
> Готовы: аутентификация и роли, справочники, субъекты, журнал аудита с хеш-цепочкой, конструктор и
> согласование форм, регистрация согласий, карточка клиента, импорт, расчёт каналов и передач, отзыв
> с каскадом и самообслуживанием, уведомления и webhooks, а также веб-интерфейс сотрудника §16 и
> встраиваемая страница клиента.
> Этап 8 добавил эксплуатационную готовность: шифрование контактов под флагом, allow-list адресов
> подписок, бизнес-метрики, политику хранения, экспорт карточки в PDF, diff версий форм, асинхронную
> массовую проверку, runbook и отчёт нагрузочного smoke.
> Что сделано и какие вопросы остаются — в [`docs/DECISIONS.md`](docs/DECISIONS.md).

## Запуск за 5 минут

Нужны только Docker и Docker Compose.

```bash
git clone <репозиторий> cus && cd cus
INCONSENSU_PROFILES=demo docker compose up -d --build   # postgres + mailpit + приложение с демо-данными
./scripts/demo.sh                                # сквозная проверка реализованного объёма
```

Профиль `demo` создаёт вымышленных клиентов (Травин, Чкалов, Бондаренко), опубликованную форму согласия
и по одному пользователю на каждую роль Приложения E — логин совпадает с кодом роли в нижнем регистре
(`admin`, `dpo`, `lawyer`, `manager`, `marketing`, `integration`, `auditor`), пароль у всех
`demo-password-2026`. Этими же учётными записями выполняется вход в интерфейс на
<http://localhost:8080/ui/login> (роль `integration` — служебная, рабочего места у неё нет). **Профиль предназначен только для демонстрации, в эксплуатации его включать нельзя.**

После старта доступны:

| Что | Адрес |
|---|---|
| Рабочее место сотрудника | <http://localhost:8080/ui/> |
| REST API | <http://localhost:8080/api/v1> |
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI (YAML) | <http://localhost:8080/v3/api-docs.yaml> |
| Health | <http://localhost:8080/actuator/health> |
| Метрики Prometheus | <http://localhost:8080/actuator/prometheus> |
| Почтовая заглушка Mailpit | <http://localhost:8025> |

Остановить: `docker compose down` (с удалением данных — `docker compose down -v`).

## Разработка

Нужны JDK 21 (Axiom JDK, Liberica или другой OpenJDK-совместимый) и Docker — последний обязателен
для интеграционных тестов на Testcontainers.

```bash
make help          # список команд
make verify        # ./mvnw verify: тесты, покрытие, Spotless, Checkstyle, сверка openapi.yaml
make test          # только юнит-тесты, без Docker
make format        # ./mvnw spotless:apply
make run           # запуск с профилем dev (нужна поднятая БД: docker compose up -d postgres)
make openapi       # перегенерировать docs/openapi.yaml после изменения API
```

Если Docker поднят не через Docker Desktop, а через colima или Podman, Testcontainers нужно указать
сокет:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"   # для colima
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

`./mvnw verify` — единственный критерий готовности: сборка падает при упавшем тесте, недостаточном
покрытии слоёв `domain` и `application`, нарушении форматирования, нарушении правил Checkstyle,
нарушении архитектурных правил ArchUnit и при расхождении `docs/openapi.yaml` с кодом.

## Конфигурация

Все секреты и адреса — через переменные окружения (NFR-3); значения по умолчанию рассчитаны на
локальную разработку.

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `INCONSENSU_DB_URL` | `jdbc:postgresql://localhost:5432/inconsensu` | JDBC-адрес PostgreSQL 15/16 или Postgres Pro |
| `INCONSENSU_DB_USER` / `INCONSENSU_DB_PASSWORD` | `inconsensu` / `inconsensu` | учётные данные БД |
| `INCONSENSU_DB_POOL_SIZE` | `10` | размер пула соединений |
| `INCONSENSU_PORT` | `8080` | порт HTTP |
| `INCONSENSU_TIMEZONE` | `Europe/Moscow` | таймзона оператора для бизнес-дат (§8.7) |
| `INCONSENSU_BASE_URL` | `http://localhost:8080` | адрес установки для ссылок в письмах |
| `INCONSENSU_SMTP_HOST` / `INCONSENSU_SMTP_PORT` | `localhost` / `1025` | почтовый сервер для уведомлений |
| `INCONSENSU_SMTP_USER` / `INCONSENSU_SMTP_PASSWORD` | — | учётные данные SMTP, если требуется аутентификация |
| `INCONSENSU_MAIL_FROM` | `noreply@example.ru` | адрес отправителя уведомлений |
| `INCONSENSU_MAIL_ENABLED` | `true` | выключает отправку писем, не трогая очередь уведомлений |
| `INCONSENSU_NOTIFICATION_CRON` | `0 0 6 * * *` | время ежедневного отбора истекающих согласий (FR-9.1) |
| `INCONSENSU_OUTBOX_DELAY` | `PT30S` | период доставки событий из outbox в webhooks |
| `INCONSENSU_WEBHOOK_ALLOWED_HOSTS` | — | список разрешённых хостов подписок; пусто — без ограничений (NFR-4) |
| `INCONSENSU_WEBHOOK_REQUIRE_HTTPS` | `false` | запретить подписки по http:// |
| `INCONSENSU_CRYPTO_ENABLED` | `false` | шифрование контактов в базе (NFR-3) |
| `INCONSENSU_CRYPTO_KEY` | — | ключ AES-256 в base64; обязателен при включённом шифровании |
| `INCONSENSU_CRYPTO_PREVIOUS_KEY` | — | предыдущий ключ на время ротации |
| `SPRING_PROFILES_ACTIVE` | — | профиль при запуске напрямую: `dev`, `demo`, `oidc`, `kafka` |

Профили: `dev` — читаемые логи и подробный health; `demo` — демонстрационные (вымышленные) данные;
`oidc` — вход через внешний IdP; `kafka` — опциональная доставка событий (этап 8).

Отдельно — переменные, которые читает только `docker-compose.yml`:

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `INCONSENSU_PROFILES` | `demo` | профиль приложения в контейнере (передаётся как `SPRING_PROFILES_ACTIVE`) |
| `INCONSENSU_DB_PORT` | `5432` | порт PostgreSQL на хосте |
| `INCONSENSU_SMTP_PORT` / `INCONSENSU_MAILPIT_UI_PORT` | `1025` / `8025` | порты Mailpit на хосте |

Сборка образа требует BuildKit (входит в Docker 23+ и в `docker buildx`); `make docker-build`
включает его явно.

## Структура

```text
docs/            ТЗ, OpenAPI, архитектура, ADR, открытые вопросы
config/          конфигурация Checkstyle
scripts/         demo.sh — сквозной сценарий §11
src/main/java/ru/example/cus/
  common/        общие value objects, ошибки, конфигурация, время
  catalog/       типы и формы согласий, workflow согласования
  registry/      субъекты, согласия, статусы, карточка клиента, отзыв
  thirdparty/    справочник третьих лиц, передачи, выгрузки партнёрам
  channels/      расчёт разрешённых каналов коммуникации
  notification/  правила уведомлений, outbox, email и webhooks
  integration/   импорт, экспорт, подписки, self-service API
  audit/         неизменяемый журнал с хеш-цепочкой, журнал доступа к ПДн
  iam/           пользователи, роли, аутентификация
  ui/            веб-интерфейс сотрудника и страница самообслуживания
src/main/resources/db/migration/   миграции Flyway (чистый SQL)
```

Внутри каждого модуля — слои `api`, `application`, `domain`, `infrastructure`; правила зависимостей
между ними закреплены тестами ArchUnit (§5, §11).

## Документация

- [`docs/TZ.md`](docs/TZ.md) — техническое задание (источник требований)
- [`docs/architecture.md`](docs/architecture.md) — архитектура, C4 уровни 1–2
- [`docs/openapi.yaml`](docs/openapi.yaml) — спецификация API, генерируется при сборке
- [`docs/ui-walkthrough.md`](docs/ui-walkthrough.md) — проход сквозного сценария через веб-интерфейс
- [`docs/webhooks.md`](docs/webhooks.md) — подписка на события для внешних систем: формат, подпись, повторы
- [`docs/import-format.md`](docs/import-format.md) — формат файла импорта согласий
- [`docs/DECISIONS.md`](docs/DECISIONS.md) — принятые решения (ADR)
- [`docs/runbook.md`](docs/runbook.md) — эксплуатация: инциденты, восстановление, ротация ключей, ретенция
- [`docs/performance.md`](docs/performance.md) — нагрузочный smoke, планы запросов и что подтверждено
- [`docs/OPEN_QUESTIONS.md`](docs/OPEN_QUESTIONS.md) — вопросы к заказчику
