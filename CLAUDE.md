# In consensu — центр управления согласиями

Ты разрабатываешь эту систему с нуля по ТЗ `docs/TZ.md` (читать целиком перед работой). Задача и
состав результата — раздел «Постановка задачи для Claude Code». Текущий этап — см. `docs/DECISIONS.md`.

## Стек

Java 21, Spring Boot 3.4, Maven (`./mvnw`), PostgreSQL 16, Flyway (чистый SQL), MapStruct, Lombok,
springdoc-openapi, Testcontainers, ArchUnit, JaCoCo, Thymeleaf + HTMX (этап 7).

## Команды

- Запуск окружения: `docker compose up -d`
- Сборка и все проверки: `./mvnw verify` (он же `make verify`)
- Только юнит-тесты (без Docker): `./mvnw test`
- Форматирование: `./mvnw spotless:apply`
- Перегенерация спецификации: `make openapi`
- Демо-сценарий: `scripts/demo.sh`

## Структура

`src/main/java/ru/example/cus/<модуль>/{api,application,domain,infrastructure}`, модули:
`common`, `catalog`, `registry`, `thirdparty`, `channels`, `notification`, `integration`, `audit`,
`iam`, `ui` (§5, §12). Миграции — `src/main/resources/db/migration`.

Тесты: `*Test` — юнит-тесты (surefire, без Docker), `*IT` — интеграционные на Testcontainers
(failsafe). База для интеграционных тестов — `ru.example.cus.support.AbstractIntegrationTest`.

## Правила

- Работать по этапам §13 ТЗ строго последовательно, один этап за раз, не смешивать этапы в одном
  наборе изменений. Этап закрыт только при зелёном `./mvnw verify` и обновлённых `docs/openapi.yaml`,
  `README.md` и профильных документах. После этапа — краткий отчёт по п. 12 §14 и ожидание
  подтверждения.
- Не выдумывать требования: неясности — в `docs/OPEN_QUESTIONS.md`, принятые решения — ADR в
  `docs/DECISIONS.md` (контекст → решение → последствия). Не блокироваться, если решение обратимо.
- Тесты — часть каждой задачи, а не отдельный этап. Порог покрытия `domain` + `application` — 80 %.
- Миграции Flyway неизменяемы после коммита; исправления — новой миграцией
  `V<yyyyMMddHHmm>__<описание>.sql`; `ddl-auto` только `validate`.
- Никаких ПДн в логах и текстах ошибок; тестовые и демо-данные только вымышленные.
- Пользовательские тексты (статусы, письма, ошибки, справочники) — по-русски; идентификаторы кода,
  API и БД, комментарии и javadoc — по-английски. Комментарии короткие и только там, где неочевидно
  «почему».
- Коммиты небольшие, Conventional Commits с номерами требований, например
  `feat(registry): FR-4.1 batch consent registration with idempotency`.
- Новые зависимости — только с ADR и проверкой лицензии (Apache 2.0 / MIT / BSD / EPL);
  предпочитать экосистему Spring.
- Опубликованные контракты API не менять задним числом без ADR и обновления `docs/openapi.yaml`.
- Всё помеченное «опционально» и «этап 8» — только после обязательного объёма.
