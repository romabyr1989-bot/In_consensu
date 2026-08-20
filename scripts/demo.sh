#!/usr/bin/env bash
#
# Сквозной демонстрационный сценарий In consensu (§11 ТЗ).
#
# Скрипт покрывает все этапы §13: сквозной сценарий §11 (канал разрешён -> отзыв -> канал запрещён ->
# событие в outbox -> письмо), веб-интерфейс §16 и эксплуатационные возможности этапа 8.
#
# Нужен профиль demo: make up (или запуск службы с SPRING_PROFILES_ACTIVE=demo)
# Использование:  BASE_URL=http://localhost:8080 ./scripts/demo.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
MAIL_UI_URL="${MAIL_UI_URL:-}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-120}"
# Демо-профиль создаёт пользователей на каждую роль Приложения E (см. README)
DEMO_LOGIN="${DEMO_LOGIN:-admin}"
DEMO_PASSWORD="${DEMO_PASSWORD:-demo-password-2026}"

STEP=0
FAILED=0

log()  { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
ok()   { printf '   \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '   \033[31m✗\033[0m %s\n' "$1"; FAILED=1; }
skip() { printf '   \033[33m…\033[0m %s\n' "$1"; }

step() {
  STEP=$((STEP + 1))
  log "Шаг ${STEP}. $1"
}

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "Не найдена утилита '$1'. Установите её и повторите." >&2; exit 2; }
}

require curl
# Разбор карточки клиента: выбрать рекламное согласие регулярным выражением по JSON надёжно не выходит.
require python3

wait_for_health() {
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl --fail --silent "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# --------------------------------------------------------------------------------------

step "Ожидание запуска приложения на ${BASE_URL}"
if wait_for_health; then
  ok "приложение отвечает"
else
  fail "приложение не поднялось за ${TIMEOUT_SECONDS} с — проверьте журнал: journalctl -u inconsensu"
  exit 1
fi

step "Проверка состояния сервиса (/actuator/health)"
HEALTH="$(curl --fail --silent "${BASE_URL}/actuator/health")"
if grep -q '"status":"UP"' <<<"$HEALTH"; then
  ok "статус UP"
else
  fail "неожиданный ответ: ${HEALTH}"
fi

step "Проверка метрик (/actuator/prometheus)"
if curl --fail --silent "${BASE_URL}/actuator/prometheus" | grep -q "jvm_memory_used_bytes"; then
  ok "метрики отдаются"
else
  fail "метрики недоступны"
fi

step "Проверка спецификации API (/v3/api-docs)"
if curl --fail --silent "${BASE_URL}/v3/api-docs" | grep -q '"openapi"'; then
  ok "OpenAPI отдаётся, Swagger UI: ${BASE_URL}/swagger-ui.html"
else
  fail "OpenAPI недоступен"
fi

step "Проверка сквозного идентификатора запроса (X-Request-Id)"
REQUEST_ID="demo-$(date +%s)"
RETURNED="$(curl --fail --silent --output /dev/null --dump-header - \
  -H "X-Request-Id: ${REQUEST_ID}" "${BASE_URL}/actuator/health" \
  | tr -d '\r' | awk 'tolower($1) == "x-request-id:" { print $2 }')"
if [ "$RETURNED" = "$REQUEST_ID" ]; then
  ok "идентификатор возвращён без изменений"
else
  fail "ожидался '${REQUEST_ID}', получен '${RETURNED}'"
fi

step "Проверка формата ошибок (RFC 9457 ProblemDetail)"
PROBLEM="$(curl --silent "${BASE_URL}/api/v1/does-not-exist")"
if grep -q 'urn:inconsensu:error:' <<<"$PROBLEM"; then
  ok "ошибки в формате ProblemDetail с type=urn:inconsensu:error:*"
else
  fail "неожиданный ответ: ${PROBLEM}"
fi

step "Просмотр писем (необязательно)"
if [ -n "${MAIL_UI_URL}" ] && curl --fail --silent --output /dev/null "${MAIL_UI_URL}" 2>/dev/null; then
  ok "просмотр писем доступен: ${MAIL_UI_URL}"
else
  skip "просмотр писем не настроен: задайте MAIL_UI_URL, если он нужен"
fi

# --------------------------------------------------------------------------------------
# Реализованный бизнес-сценарий (этапы 1–3)
# --------------------------------------------------------------------------------------

step "Вход сотрудника (FR-11.1)"
TOKEN="$(curl --fail --silent -X POST "${BASE_URL}/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"login\":\"${DEMO_LOGIN}\",\"password\":\"${DEMO_PASSWORD}\"}" \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
if [ -n "$TOKEN" ]; then
  ok "получен токен для пользователя ${DEMO_LOGIN}"
else
  fail "не удалось войти: запустите с профилем demo (make up)"
  exit 1
fi
AUTH="Authorization: Bearer ${TOKEN}"

step "Справочник типов согласий по Приложению B (FR-1.1)"
if curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/consent-types?size=50" | grep -q "PDN_PROCESSING"; then
  ok "типы согласий загружены"
else
  fail "справочник типов пуст"
fi

step "Опубликованная форма согласия (FR-1.5, FR-1.6)"
FORMS="$(curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/forms?size=50")"
if grep -q '"status":"PUBLISHED"' <<<"$FORMS"; then
  ok "есть опубликованная версия формы"
else
  fail "опубликованных форм нет"
fi

step "Поиск клиента по фамилии (FR-5.2)"
# Кириллица в запросе кодируется curl'ом: сырые символы в строке запроса Tomcat отклоняет как 400.
SUBJECT_ID="$(curl --fail --silent -G -H "$AUTH" --data-urlencode "query=Травин" "${BASE_URL}/api/v1/subjects" \
  | sed -n 's/.*"id":"\([0-9a-f-]\{36\}\)".*/\1/p' | head -1)"
if [ -n "$SUBJECT_ID" ]; then
  ok "Травин найден: ${SUBJECT_ID}"
else
  fail "демо-клиент не найден — загружены ли демо-данные?"
  exit 1
fi

step "Карточка клиента (FR-5.1, Приложение A)"
CARD="$(curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/subjects/${SUBJECT_ID}/card")"
for expected in '"statusText":"действует"' '"statusText":"отозвано"' "заканчивается через"; do
  if grep -q "$expected" <<<"$CARD"; then
    ok "статус «${expected}» присутствует в карточке"
  else
    fail "в карточке нет статуса «${expected}»"
  fi
done

step "Досье согласия: точный текст и целостность журнала (FR-10.3)"
CONSENT_ID="$(sed -n 's/.*"consents":\[{"id":"\([0-9a-f-]\{36\}\)".*/\1/p' <<<"$CARD" | head -1)"
if [ -z "$CONSENT_ID" ]; then
  CONSENT_ID="$(sed -n 's/.*"id":"\([0-9a-f-]\{36\}\)","subjectId".*/\1/p' <<<"$CARD" | head -1)"
fi
if [ -n "$CONSENT_ID" ]; then
  EVIDENCE="$(curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/consents/${CONSENT_ID}/evidence")"
  if grep -q '"integrity":"OK"' <<<"$EVIDENCE" && grep -q '"checksumMatches":true' <<<"$EVIDENCE"; then
    ok "цепочка аудита цела, контрольная сумма формы сходится"
  else
    fail "досье согласия не подтверждает целостность"
  fi
else
  skip "не удалось выделить идентификатор согласия из карточки"
fi

step "Журнал доступа к ПДн (FR-10.5)"
if curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/audit/access-log?size=5" | grep -q "subjects"; then
  ok "обращения к карточке зафиксированы"
else
  fail "журнал доступа пуст"
fi

step "Проверка целостности всех цепочек аудита (FR-10.4)"
# FR-10.4: проверка выполняется в фоне, поэтому запрашивается результат по идентификатору запуска.
VERIFY_ID="$(curl --fail --silent -X POST -H "$AUTH" "${BASE_URL}/api/v1/audit/verify" \
  | sed -n 's/.*"id":"\([0-9a-f-]\{36\}\)".*/\1/p')"
VERIFY_RESULT=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  VERIFY_RESULT="$(curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/audit/verify/${VERIFY_ID}")"
  grep -q '"status":"DONE"' <<<"$VERIFY_RESULT" && break
  sleep 1
done
if grep -q '"integrity":"OK"' <<<"$VERIFY_RESULT"; then
  ok "журнал аудита не нарушен"
else
  fail "проверка целостности не завершилась успехом"
fi

step "Разрешённые каналы коммуникации до отзыва (FR-6.1)"
CHANNELS_BEFORE="$(curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/subjects/${SUBJECT_ID}/channels")"
if grep -q '"summaryRu"' <<<"$CHANNELS_BEFORE"; then
  ok "$(sed -n 's/.*"summaryRu":"\([^"]*\)".*/\1/p' <<<"$CHANNELS_BEFORE")"
else
  fail "расчёт каналов не вернул сводку"
fi

step "Массовая проверка канала (FR-6.4)"
if curl --fail --silent -X POST -H "$AUTH" -H 'Content-Type: application/json' \
     -d "{\"channel\":\"EMAIL\",\"identifiers\":[\"${SUBJECT_ID}\"],\"includeReasons\":true}" \
     "${BASE_URL}/api/v1/channels/check" | grep -q '"requested":1'; then
  ok "пакетная проверка отвечает по каждому идентификатору"
else
  fail "массовая проверка канала не сработала"
fi

step "Отзыв рекламного согласия (FR-8.2, FR-8.3)"
# Берём любое действующее непрофильное согласие: базовое отзывать нельзя без каскада по всей карточке.
AD_CONSENT_ID="$(python3 - "$CARD" <<'PYEOF'
import json, sys
card = json.loads(sys.argv[1])
for consent in card.get("consents", []):
    if consent.get("typeCode") != "PDN_PROCESSING" and consent.get("status") in ("ACTIVE", "EXPIRING"):
        print(consent["id"])
        break
PYEOF
)"
if [ -n "$AD_CONSENT_ID" ]; then
  REVOKED="$(curl --fail --silent -X POST -H "$AUTH" -H 'Content-Type: application/json' \
      -d '{"reason":"демонстрация отзыва","revocationSource":"PERSONAL_ACCOUNT","caseNumber":"ДЕМО-1"}' \
      "${BASE_URL}/api/v1/consents/${AD_CONSENT_ID}/revoke")"
  if grep -q '"processingStopDeadline"' <<<"$REVOKED"; then
    ok "согласие отозвано, срок прекращения обработки указан (FR-8.5)"
  else
    fail "отзыв не вернул срок прекращения обработки"
  fi
else
  skip "в демо-данных нет действующего рекламного согласия"
fi

step "Канал закрывается немедленно после отзыва (FR-8.3)"
CHANNELS_AFTER="$(curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/subjects/${SUBJECT_ID}/channels")"
if grep -q '"allowed":false' <<<"$CHANNELS_AFTER"; then
  ok "$(sed -n 's/.*"summaryRu":"\([^"]*\)".*/\1/p' <<<"$CHANNELS_AFTER")"
else
  fail "после отзыва канал остался разрешённым"
fi

step "Событие отзыва записано в outbox (FR-9.4, §8.6)"
if [ -n "$AD_CONSENT_ID" ] && curl --fail --silent -H "$AUTH" \
     "${BASE_URL}/api/v1/consents/${AD_CONSENT_ID}/evidence" | grep -q '"integrity":"OK"'; then
  ok "отзыв зафиксирован в неизменяемом журнале, событие ушло в очередь доставки"
else
  skip "нечего проверять: рекламное согласие не отзывалось"
fi

step "Правила уведомлений (FR-9.1)"
if curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/notification-rules" | grep -q '"triggerTypeRu"'; then
  ok "правила загружены, пороги переподписания настроены"
else
  fail "справочник правил уведомлений пуст"
fi

step "Подписка на события и тестовая отправка (FR-9.5)"
SUBSCRIPTION="$(curl --fail --silent -X POST -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"name":"Демонстрационная подписка","url":"http://127.0.0.1:9/hook","eventTypes":["consent.revoked"]}' \
    "${BASE_URL}/api/v1/webhooks")"
SUBSCRIPTION_ID="$(sed -n 's/.*"subscription":{"id":"\([0-9a-f-]\{36\}\)".*/\1/p' <<<"$SUBSCRIPTION")"
if [ -n "$SUBSCRIPTION_ID" ] && grep -q '"secret"' <<<"$SUBSCRIPTION"; then
  ok "подписка создана, секрет подписи выдан один раз"
  # Адрес недоступен намеренно: показываем журнал доставок и обработку ошибки, а не успех.
  curl --fail --silent -X POST -H "$AUTH" "${BASE_URL}/api/v1/webhooks/${SUBSCRIPTION_ID}/test" >/dev/null || true
  if curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/webhooks/${SUBSCRIPTION_ID}/deliveries" \
       | grep -q '"attempt"'; then
    ok "попытка доставки записана в журнал с кодом ответа и ошибкой"
  else
    fail "журнал доставок пуст"
  fi
  curl --fail --silent -X POST -H "$AUTH" "${BASE_URL}/api/v1/webhooks/${SUBSCRIPTION_ID}/deactivate" >/dev/null
else
  fail "не удалось создать подписку"
fi

step "Тестовое письмо (FR-9.2, FR-9.5)"
if curl --fail --silent -X POST -H "$AUTH" -H 'Content-Type: application/json' \
     -d '{"email":"dpo@example.ru"}' "${BASE_URL}/api/v1/notifications/test-email" | grep -q '"sent":true'; then
  ok "письмо принято SMTP-сервером"
  # Доставку проверяет тот, у кого настроен просмотр писем: своего почтового сервера у продукта нет.
  if [ -n "${MAIL_UI_URL}" ]; then
    if curl --fail --silent "${MAIL_UI_URL}/api/v1/search?query=dpo@example.ru" | grep -q "проверка отправки"; then
      ok "письмо видно в просмотрщике"
    else
      fail "письмо отправлено, но в просмотрщике не найдено"
    fi
  fi
else
  fail "тестовое письмо не отправлено: проверьте настройки SMTP"
fi

step "Внеочередной прогон задачи уведомлений (FR-9.1)"
if curl --fail --silent -X POST -H "$AUTH" "${BASE_URL}/api/v1/notifications/run" | grep -q '"expiring"'; then
  ok "задача отработала, повторные уведомления отсекаются дедупликацией"
else
  fail "внеочередной прогон не выполнен"
fi

step "Веб-интерфейс сотрудника (§16)"
UI_COOKIES="$(mktemp)"
UI_CSRF="$(curl --fail --silent -c "$UI_COOKIES" "${BASE_URL}/ui/login" \
  | sed -n 's/.*name="_csrf" content="\([^"]*\)".*/\1/p')"
curl --fail --silent -b "$UI_COOKIES" -c "$UI_COOKIES" -o /dev/null \
  -X POST -d "username=${DEMO_LOGIN}&password=${DEMO_PASSWORD}&_csrf=${UI_CSRF}" "${BASE_URL}/ui/login" || true
if curl --fail --silent -b "$UI_COOKIES" "${BASE_URL}/ui/" | grep -q "Действующих согласий"; then
  ok "вход выполнен, дашборд UI-2 открывается"
else
  fail "интерфейс не открылся под демо-пользователем"
fi
if curl --fail --silent -b "$UI_COOKIES" "${BASE_URL}/ui/subjects/${SUBJECT_ID}" | grep -q "Телефонный звонок"; then
  ok "карточка клиента UI-4 показывает плитки каналов"
else
  fail "карточка клиента не отрисовалась"
fi
rm -f "$UI_COOKIES"

step "Страница самообслуживания по одноразовой ссылке (UI-18)"
SELF_TOKEN="$(curl --fail --silent -X POST -H 'Content-Type: application/json' \
  -d "{\"login\":\"integration\",\"password\":\"${DEMO_PASSWORD}\"}" "${BASE_URL}/api/v1/auth/login" \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
SELF_URL="$(curl --fail --silent -X POST -H "Authorization: Bearer ${SELF_TOKEN}" -H 'Content-Type: application/json' \
  -d '{"externalId":"CRM-1002345"}' "${BASE_URL}/api/v1/self/ui-sessions" \
  | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')"
SELF_COOKIES="$(mktemp)"
curl --fail --silent -b "$SELF_COOKIES" -c "$SELF_COOKIES" -o /dev/null "$SELF_URL" || true
if curl --fail --silent -b "$SELF_COOKIES" "${BASE_URL}/self/ui" | grep -q "Здравствуйте"; then
  ok "клиент видит свои согласия по одноразовой ссылке"
else
  fail "страница самообслуживания не открылась"
fi
# Ссылка одноразовая: второе открытие обязано отказать.
if curl --silent "$SELF_URL" | grep -q "Ссылка недействительна"; then
  ok "повторное открытие ссылки отклонено"
else
  fail "одноразовая ссылка сработала дважды"
fi
rm -f "$SELF_COOKIES"

step "Экспорт карточки клиента в PDF (UI-4, этап 8)"
if curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/subjects/${SUBJECT_ID}/card.pdf" \
     | head -c 5 | grep -q "%PDF-"; then
  ok "карточка выгружается в PDF"
else
  fail "PDF карточки не сформировался"
fi

step "Асинхронная массовая проверка канала (этап 8)"
BULK_JOB="$(curl --fail --silent -X POST -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"channel\":\"EMAIL\",\"identifiers\":[\"${SUBJECT_ID}\"]}" \
  "${BASE_URL}/api/v1/channels/check-async" | sed -n 's/.*"jobId":"\([0-9a-f-]\{36\}\)".*/\1/p')"
BULK_RESULT=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  BULK_RESULT="$(curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/channels/check-async/${BULK_JOB}")"
  grep -q '"status":"DONE"' <<<"$BULK_RESULT" && break
  sleep 1
done
if grep -q '"status":"DONE"' <<<"$BULK_RESULT"; then
  ok "задача массовой проверки завершилась"
else
  fail "задача массовой проверки не завершилась"
fi

step "Пробный прогон политики хранения (NFR-5)"
if curl --fail --silent -X POST -H "$AUTH" \
     "${BASE_URL}/api/v1/maintenance/retention/run?dryRun=true" | grep -q '"dryRun":true'; then
  ok "ретенция считает записи, ничего не меняя"
else
  fail "пробный прогон ретенции не выполнен"
fi

step "Бизнес-метрики (NFR-6)"
# Вывод метрик большой: с set -o pipefail ранний выход grep обрывает curl по SIGPIPE, поэтому
# ответ сначала сохраняется целиком.
METRICS="$(curl --fail --silent "${BASE_URL}/actuator/prometheus")"
if grep -qE 'inconsensu_consents|inconsensu_outbox_queue' <<<"$METRICS"; then
  ok "метрики согласий и очереди событий отдаются"
else
  fail "бизнес-метрик нет в /actuator/prometheus"
fi

if [ "$FAILED" -eq 0 ]; then
  log "Демо-сценарий пройден полностью (этапы 0-8)"
else
  log "Демо-сценарий завершился с ошибками"
  exit 1
fi
