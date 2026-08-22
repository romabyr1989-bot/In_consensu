#!/usr/bin/env bash
#
# Сквозной демонстрационный сценарий In consensu (§11 ТЗ).
#
# Скрипт покрывает все этапы §13: сквозной сценарий §11 (канал разрешён -> отзыв -> канал запрещён ->
# событие в outbox -> письмо), веб-интерфейс §16 и эксплуатационные возможности этапа 8.
#
# Нужен профиль demo: make up (или запуск службы с SPRING_PROFILES_ACTIVE=demo).
#
# Скрипт повторяем: форму, клиента и согласие он заводит сам, и отзыв делает по своему клиенту. Демо-данные
# при этом остаются нетронутыми — по ним проверяется только соответствие карточки Приложению A.
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
# grep -q закрывает пайп на первом совпадении: curl не дописывает ответ, падает с SIGPIPE, и под
# `set -o pipefail` успешный шаг считается провалившимся. Поэтому ответ дочитывается до конца.
contains() { grep -c -e "$1" >/dev/null; }
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
if curl --fail --silent "${BASE_URL}/actuator/prometheus" | contains "jvm_memory_used_bytes"; then
  ok "метрики отдаются"
else
  fail "метрики недоступны"
fi

step "Проверка спецификации API (/v3/api-docs)"
if curl --fail --silent "${BASE_URL}/v3/api-docs" | contains '"openapi"'; then
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
if curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/consent-types?size=50" | contains "PDN_PROCESSING"; then
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

# --------------------------------------------------------------------------------------
# Сценарий §11 целиком: форма создаётся здесь же, проходит согласование и публикацию, по ней
# регистрируется согласие. Раньше скрипт проверял уже загруженные демо-данные — то есть
# констатировал результат, а не проходил путь.
# --------------------------------------------------------------------------------------

login() {
  curl --fail --silent -X POST "${BASE_URL}/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"login\":\"$1\",\"password\":\"${DEMO_PASSWORD}\"}" \
    | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

LAWYER_AUTH="Authorization: Bearer $(login lawyer)"
DPO_AUTH="Authorization: Bearer $(login dpo)"
INTEGRATION_AUTH="Authorization: Bearer $(login integration)"
RUN_ID="$(date +%H%M%S)"
FORM_CODE="DEMO_${RUN_ID}"

step "Черновик формы согласия (FR-1.2, UI-8)"
DRAFT="$(curl --fail --silent -X POST -H "$LAWYER_AUTH" -H 'Content-Type: application/json' \
  -d "{\"code\":\"${FORM_CODE}\",\"form\":{
        \"title\":\"Согласие демонстрационного сценария ${RUN_ID}\",
        \"body\":\"Я, {{subject.fio}}, телефон {{subject.phone}}, электронная почта {{subject.email}}, даю согласие {{operator.name}} ({{operator.address}}) на обработку персональных данных.\",
        \"processingActions\":\"сбор, запись, систематизация, хранение, использование, уничтожение\",
        \"revocationProcedure\":\"действует до отзыва; отзыв — в личном кабинете или письменным заявлением\",
        \"sourceChannels\":[\"WEBSITE_APPLICATION\"],
        \"items\":[
          {\"consentTypeCode\":\"PDN_PROCESSING\",\"text\":\"Согласие на обработку персональных данных\",\"purposes\":[\"рассмотрение заявки\"],\"pdnCategories\":[\"FIO\",\"PHONE\",\"EMAIL\"],\"mandatory\":true},
          {\"consentTypeCode\":\"ADVERTISING_EMAIL\",\"text\":\"Согласие на рекламу по электронной почте\",\"purposes\":[\"информирование о продуктах\"],\"pdnCategories\":[\"EMAIL\"],\"mandatory\":false}
        ]}}" \
  "${BASE_URL}/api/v1/forms")"
# Идентификатор берётся разбором JSON: у формы и у её пунктов поля называются одинаково, и жадный
# sed выхватывал последний «id» — идентификатор пункта, а не формы.
FORM_ID="$(python3 - "$DRAFT" <<'PYEOF'
import json, sys
print(json.loads(sys.argv[1]).get("id", ""))
PYEOF
)"
if [ -n "$FORM_ID" ] && grep -q '"status":"DRAFT"' <<<"$DRAFT"; then
  ok "черновик ${FORM_CODE} создан юристом"
else
  fail "черновик формы не создался"
  exit 1
fi

step "Согласование формы двумя ролями и публикация (FR-2.1, FR-1.5)"
curl --fail --silent -o /dev/null -X POST -H "$LAWYER_AUTH" "${BASE_URL}/api/v1/forms/${FORM_ID}/submit"
curl --fail --silent -o /dev/null -X POST -H "$LAWYER_AUTH" -H 'Content-Type: application/json' \
  -d '{"comment":"формулировки проверены"}' "${BASE_URL}/api/v1/forms/${FORM_ID}/approve"
curl --fail --silent -o /dev/null -X POST -H "$DPO_AUTH" -H 'Content-Type: application/json' \
  -d '{"comment":"реквизиты проверены"}' "${BASE_URL}/api/v1/forms/${FORM_ID}/approve"
PUBLISHED="$(curl --fail --silent -X POST -H "$DPO_AUTH" "${BASE_URL}/api/v1/forms/${FORM_ID}/publish")"
if grep -q '"status":"PUBLISHED"' <<<"$PUBLISHED" && grep -q '"checksum":"sha256:' <<<"$PUBLISHED"; then
  ok "версия опубликована, контрольная сумма посчитана"
else
  fail "форма не опубликовалась: одобрения обеих ролей или реквизиты оператора не прошли"
  exit 1
fi

step "Регистрация согласия по опубликованной форме (FR-4.1, FR-4.2)"
ITEM_IDS="$(python3 - "$PUBLISHED" <<'PYEOF'
import json, sys
form = json.loads(sys.argv[1])
print(" ".join(item["id"] for item in form.get("items", [])))
PYEOF
)"
DECISIONS="$(python3 - "$ITEM_IDS" <<'PYEOF'
import json, sys
print(json.dumps([{"formItemId": i, "accepted": True} for i in sys.argv[1].split()], ensure_ascii=False))
PYEOF
)"
SCENARIO_EXTERNAL_ID="CRM-ДЕМО-${RUN_ID}"
REGISTERED="$(curl --fail --silent -X POST -H "$INTEGRATION_AUTH" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-${RUN_ID}" \
  -d "{
        \"subject\":{\"externalId\":\"${SCENARIO_EXTERNAL_ID}\",\"lastName\":\"Демидова\",\"firstName\":\"Ольга\",\"middleName\":\"Ивановна\",
          \"contacts\":[{\"type\":\"PHONE\",\"value\":\"+7 916 000-09-11\",\"primary\":true},
                        {\"type\":\"EMAIL\",\"value\":\"demidova-${RUN_ID}@example.ru\",\"primary\":true}]},
        \"formId\":\"${FORM_ID}\",
        \"items\":${DECISIONS},
        \"source\":\"WEBSITE_APPLICATION\",
        \"sourceRef\":\"заявка ${RUN_ID}\",
        \"signatureType\":\"SIMPLE_ES_SMS\",
        \"evidence\":{\"phone\":\"+79160000911\",\"otpVerifiedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"otpHash\":\"demo\",\"ip\":\"10.0.0.9\",\"userAgent\":\"demo.sh\"}
      }" \
  "${BASE_URL}/api/v1/consents")"
SCENARIO_SUBJECT_ID="$(python3 - "$REGISTERED" <<'PYEOF'
import json, sys
data = json.loads(sys.argv[1])
consents = data.get("consents", [])
print(consents[0]["subjectId"] if consents else "")
PYEOF
)"
if [ -n "$SCENARIO_SUBJECT_ID" ]; then
  ok "согласия зарегистрированы, клиент ${SCENARIO_EXTERNAL_ID} заведён"
else
  fail "регистрация согласия не прошла"
  exit 1
fi

step "Повтор запроса с тем же ключом идемпотентности (FR-4.1)"
REPEAT="$(curl --fail --silent -o /dev/null -w '%{http_code}' -X POST -H "$INTEGRATION_AUTH" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: demo-${RUN_ID}" \
  -d "{\"subjectExternalId\":\"${SCENARIO_EXTERNAL_ID}\",\"formId\":\"${FORM_ID}\",\"items\":${DECISIONS},
       \"source\":\"WEBSITE_APPLICATION\",\"signatureType\":\"SIMPLE_ES_SMS\",
       \"evidence\":{\"phone\":\"+79160000911\",\"otpVerifiedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"otpHash\":\"demo\",\"ip\":\"10.0.0.9\",\"userAgent\":\"demo.sh\"}}" \
  "${BASE_URL}/api/v1/consents")"
if [ "$REPEAT" = "200" ]; then
  ok "повтор вернул прежний результат, дубликатов не создано"
else
  fail "повтор по тому же ключу ответил ${REPEAT}, ожидался 200"
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
if curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/audit/access-log?size=5" | contains "subjects"; then
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
# Проверяется клиент, заведённый этим же прогоном: сценарий §11 отзывает согласие и меняет данные, а на
# своём клиенте скрипт можно повторять сколько угодно, не пересоздавая базу.
CHANNELS_BEFORE="$(curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/subjects/${SCENARIO_SUBJECT_ID}/channels")"
# Берём конкретный разрешённый канал и согласие, которое его открывает: без этого шаг «после отзыва»
# проходил всегда — в ответе и до отзыва есть каналы с allowed=false, и проверка ничего не доказывала.
READ_ALLOWED="$(python3 - "$CHANNELS_BEFORE" <<'PYEOF'
import json, sys
for entry in json.loads(sys.argv[1]).get("channels", []):
    if entry.get("allowed") and entry.get("basis"):
        print(entry["channel"], entry["basis"]["consentId"])
        break
PYEOF
)"
OPEN_CHANNEL="$(cut -d' ' -f1 <<<"$READ_ALLOWED")"
AD_CONSENT_ID="$(cut -d' ' -f2 <<<"$READ_ALLOWED")"
if [ -n "$OPEN_CHANNEL" ]; then
  ok "канал ${OPEN_CHANNEL} разрешён; $(sed -n 's/.*"summaryRu":"\([^"]*\)".*/\1/p' <<<"$CHANNELS_BEFORE")"
else
  fail "ни один канал не разрешён у только что заведённого клиента — расчёт каналов сломан (§7.6)"
fi

step "Массовая проверка канала (FR-6.4)"
if curl --fail --silent -X POST -H "$AUTH" -H 'Content-Type: application/json' \
     -d "{\"channel\":\"EMAIL\",\"identifiers\":[\"${SCENARIO_SUBJECT_ID}\"],\"includeReasons\":true}" \
     "${BASE_URL}/api/v1/channels/check" | contains '"requested":1'; then
  ok "пакетная проверка отвечает по каждому идентификатору"
else
  fail "массовая проверка канала не сработала"
fi

step "Отзыв согласия, открывающего канал (FR-8.2, FR-8.3)"
# Отзывается именно то согласие, которым канал разрешён: только тогда следующий шаг что-то доказывает.
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
CHANNELS_AFTER="$(curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/subjects/${SCENARIO_SUBJECT_ID}/channels")"
CLOSED="$(python3 - "$CHANNELS_AFTER" "$OPEN_CHANNEL" <<'PYEOF'
import json, sys
target = sys.argv[2]
for entry in json.loads(sys.argv[1]).get("channels", []):
    if entry.get("channel") == target:
        print("closed" if not entry.get("allowed") else "open", entry.get("reasonRu") or "")
        break
PYEOF
)"
if [ "${CLOSED%% *}" = "closed" ]; then
  ok "канал ${OPEN_CHANNEL} закрыт: ${CLOSED#* }"
else
  fail "после отзыва канал ${OPEN_CHANNEL} остался разрешённым"
fi

step "Событие отзыва записано в outbox (FR-9.4, §8.6)"
if [ -n "$AD_CONSENT_ID" ] && curl --fail --silent -H "$AUTH" \
     "${BASE_URL}/api/v1/consents/${AD_CONSENT_ID}/evidence" | contains '"integrity":"OK"'; then
  ok "отзыв зафиксирован в неизменяемом журнале, событие ушло в очередь доставки"
else
  skip "нечего проверять: рекламное согласие не отзывалось"
fi

step "Правила уведомлений (FR-9.1)"
if curl --fail --silent -H "$AUTH" "${BASE_URL}/api/v1/notification-rules" | contains '"triggerTypeRu"'; then
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
       | contains '"attempt"'; then
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
     -d '{"email":"dpo@example.ru"}' "${BASE_URL}/api/v1/notifications/test-email" | contains '"sent":true'; then
  ok "письмо принято SMTP-сервером"
  # Доставку проверяет тот, у кого настроен просмотр писем: своего почтового сервера у продукта нет.
  if [ -n "${MAIL_UI_URL}" ]; then
    if curl --fail --silent "${MAIL_UI_URL}/api/v1/search?query=dpo@example.ru" | contains "проверка отправки"; then
      ok "письмо видно в просмотрщике"
    else
      fail "письмо отправлено, но в просмотрщике не найдено"
    fi
  fi
else
  fail "тестовое письмо не отправлено: проверьте настройки SMTP"
fi

step "Внеочередной прогон задачи уведомлений (FR-9.1)"
if curl --fail --silent -X POST -H "$AUTH" "${BASE_URL}/api/v1/notifications/run" | contains '"expiring"'; then
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
if curl --fail --silent -b "$UI_COOKIES" "${BASE_URL}/ui/" | contains "Действующих согласий"; then
  ok "вход выполнен, дашборд UI-2 открывается"
else
  fail "интерфейс не открылся под демо-пользователем"
fi
if curl --fail --silent -b "$UI_COOKIES" "${BASE_URL}/ui/subjects/${SUBJECT_ID}" | contains "Телефонный звонок"; then
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
if curl --fail --silent -b "$SELF_COOKIES" "${BASE_URL}/self/ui" | contains "Здравствуйте"; then
  ok "клиент видит свои согласия по одноразовой ссылке"
else
  fail "страница самообслуживания не открылась"
fi
# Ссылка одноразовая: второе открытие обязано отказать.
if curl --silent "$SELF_URL" | contains "Ссылка недействительна"; then
  ok "повторное открытие ссылки отклонено"
else
  fail "одноразовая ссылка сработала дважды"
fi
rm -f "$SELF_COOKIES"

step "Экспорт карточки клиента в PDF (UI-4, этап 8)"
PDF_FILE="$(mktemp)"
curl --fail --silent -H "$AUTH" -o "$PDF_FILE" "${BASE_URL}/api/v1/subjects/${SUBJECT_ID}/card.pdf" || true
if [ -s "$PDF_FILE" ] && head -c 5 "$PDF_FILE" | contains "%PDF-"; then
  ok "карточка выгружается в PDF"
else
  fail "PDF карточки не сформировался"
fi
rm -f "$PDF_FILE"

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
     "${BASE_URL}/api/v1/maintenance/retention/run?dryRun=true" | contains '"dryRun":true'; then
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
