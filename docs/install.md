# Установка на чистую операционную систему

Продукт не требует Docker: это исполняемый JAR, внешняя PostgreSQL и служба systemd (ADR-0078).
Проверено на Alt Linux (Альт Сервер) и Ubuntu; подойдёт любая система с OpenJDK 21 и systemd (NFR-4).

## 1. Что нужно на сервере

| Компонент | Версия | Примечание |
|---|---|---|
| OpenJDK | 21 LTS | Axiom JDK, Liberica или системный пакет |
| PostgreSQL | 15 или 16, Postgres Pro Standard | может стоять на этом же или на отдельном сервере |
| SMTP-сервер | любой | нужен для уведомлений FR-9.2; без него приложение работает, письма не уходят |

```bash
# Alt Linux
apt-get install java-21-openjdk-headless postgresql16-server

# Ubuntu / Debian
apt-get install openjdk-21-jre-headless postgresql
```

## 2. База данных

Схему создаёт само приложение миграциями Flyway при первом запуске — руками её заводить не нужно.
Нужны только база, пользователь и права:

```bash
sudo -u postgres psql -c "CREATE USER inconsensu WITH PASSWORD 'задайте-свой-пароль';"
sudo -u postgres psql -c "CREATE DATABASE inconsensu OWNER inconsensu;"
```

### Вторая линия защиты журналов (FR-10.2)

Журналы аудита и доступа к ПДн защищены триггером, который отклоняет UPDATE и DELETE. Триггер защищает от
ошибки в приложении, но не от того, у кого есть его учётная запись к базе. Вторая линия — отзыв прав у той
роли, под которой работает служба; для неё нужны две роли: владелец схемы выполняет миграции, служба ходит
под своей.

```bash
# Владелец схемы: под ним идут миграции.
sudo -u postgres psql -c "CREATE USER inconsensu_owner WITH PASSWORD 'пароль-владельца';"
sudo -u postgres psql -c "CREATE DATABASE inconsensu OWNER inconsensu_owner;"
# Роль службы: под ней работает приложение.
sudo -u postgres psql -c "CREATE USER inconsensu WITH PASSWORD 'пароль-службы';"
sudo -u postgres psql -d inconsensu -c "GRANT USAGE ON SCHEMA public TO inconsensu;"
sudo -u postgres psql -d inconsensu -c "ALTER DEFAULT PRIVILEGES FOR ROLE inconsensu_owner IN SCHEMA public \
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO inconsensu;"
sudo -u postgres psql -d inconsensu -c "ALTER DEFAULT PRIVILEGES FOR ROLE inconsensu_owner IN SCHEMA public \
  GRANT USAGE, SELECT ON SEQUENCES TO inconsensu;"
```

В `inconsensu.env` добавьте:

```properties
INCONSENSU_DB_USER=inconsensu
INCONSENSU_DB_PASSWORD=пароль-службы
# Миграции идут под владельцем схемы, а не под ролью службы.
SPRING_FLYWAY_USER=inconsensu_owner
SPRING_FLYWAY_PASSWORD=пароль-владельца
# Имя роли службы: миграция отзовёт у неё UPDATE и DELETE на журналах.
INCONSENSU_DB_APP_ROLE=inconsensu
```

Без `INCONSENSU_DB_APP_ROLE` миграция отзыв пропускает, и журналы остаются под защитой одного триггера —
для демонстрации этого достаточно, для эксплуатации с реальными ПДн лучше завести две роли.

## 3. Учётная запись службы и каталоги

```bash
useradd --system --home-dir /opt/inconsensu --shell /sbin/nologin inconsensu
install -d -o inconsensu -g inconsensu /opt/inconsensu /var/lib/inconsensu
install -d -o root -g inconsensu -m 0750 /etc/inconsensu
```

## 4. Приложение

Соберите артефакт (`make package`) или возьмите готовый `inconsensu.jar` из поставки:

```bash
install -o inconsensu -g inconsensu -m 0644 inconsensu.jar /opt/inconsensu/inconsensu.jar
```

## 5. Настройки и секреты

Секреты передаются переменными окружения, а не файлом в репозитории (NFR-3).
Создайте `/etc/inconsensu/inconsensu.env` с правами `0640` и владельцем `root:inconsensu`:

```properties
SPRING_PROFILES_ACTIVE=prod
INCONSENSU_DB_URL=jdbc:postgresql://localhost:5432/inconsensu
INCONSENSU_DB_USER=inconsensu
INCONSENSU_DB_PASSWORD=задайте-свой-пароль

# Ключ подписи токенов, не менее 32 байт. Без него токены станут недействительными при перезапуске
# и не будут приниматься другими экземплярами (FR-11.1).
INCONSENSU_JWT_SECRET=сгенерируйте-длинную-случайную-строку

# Первый администратор заводится только при пустой таблице пользователей (FR-11.1).
INCONSENSU_ADMIN_LOGIN=admin
INCONSENSU_ADMIN_PASSWORD=задайте-свой-пароль

INCONSENSU_SMTP_HOST=smtp.example.ru
INCONSENSU_SMTP_PORT=25
INCONSENSU_BASE_URL=https://inconsensu.example.ru
INCONSENSU_TIMEZONE=Europe/Moscow

# NFR-3, этап 8: шифрование контактов в базе. Ключ хранится отдельно от дампов,
# порядок ротации — docs/runbook.md.
# INCONSENSU_CRYPTO_ENABLED=true
# INCONSENSU_CRYPTO_KEY=база64-ключ-32-байта
```

### Вход через корпоративный IdP (профиль `oidc`)

Если токены API выдаёт корпоративный IdP, добавьте профиль и адреса — код при этом не меняется (ADR-0083):

```properties
SPRING_PROFILES_ACTIVE=prod,oidc
# Ключи, которыми проверяется подпись. С ними приложение стартует, не дожидаясь IdP.
INCONSENSU_OIDC_JWK_SET_URI=https://idp.example.ru/realms/inconsensu/protocol/openid-connect/certs
INCONSENSU_OIDC_ISSUER_URI=https://idp.example.ru/realms/inconsensu
# Где IdP держит роли: у Keycloak — realm_access.roles, у других бывает roles или groups.
INCONSENSU_OIDC_ROLES_CLAIM=realm_access.roles
```

В этом профиле `/api/v1/auth/login` закрыт: токены выдаёт IdP. Вход сотрудников в интерфейс остаётся по
учётной записи оператора — рабочее место работает на серверной сессии, а не на токене.

## 6. Служба

```bash
install -o root -g root -m 0644 deploy/inconsensu.service /etc/systemd/system/inconsensu.service
systemctl daemon-reload
systemctl enable --now inconsensu
systemctl status inconsensu
```

## 7. Проверка

```bash
curl -fsS http://localhost:8080/actuator/health
```

Ответ `{"status":"UP"}` означает, что приложение поднялось и база доступна. Рабочее место сотрудника —
`http://<адрес>:8080/app/` (вход — `/ui/login`), вход под администратором из `INCONSENSU_ADMIN_LOGIN`.

Проверьте по журналу службы (`journalctl -u inconsensu`), что настройки прочитаны:

- нет строки «сгенерирован временный ключ» — значит `INCONSENSU_JWT_SECRET` применён и токены переживут
  перезапуск;
- есть строка «Создан первый администратор» — учётная запись заведена из окружения.

Если заводили две роли базы, убедитесь, что права на журналы отозваны:

```bash
psql -d inconsensu -c "select has_table_privilege('inconsensu','audit_event','UPDATE');"
```

Ожидается `f`. При этом `INSERT` и `SELECT` остаются разрешёнными — приложение продолжает писать журнал,
но не может его править.

## 8. TLS и доступ снаружи

TLS терминируется на периметре (NFR-3): приложение слушает HTTP на 8080 и рассчитывает на обратный
прокси — nginx или Angie. Наружу не должны выходить `/actuator/**`, кроме `/actuator/health`.

## Обновление

```bash
systemctl stop inconsensu
install -o inconsensu -g inconsensu -m 0644 inconsensu.jar /opt/inconsensu/inconsensu.jar
systemctl start inconsensu
```

Миграции применяются при старте. Откат версии приложения без отката базы допустим только в пределах
одной версии схемы: миграции Flyway неизменяемы и вперёд-совместимы, но не отменяются автоматически
(§14.5). Порядок восстановления и ротации ключей — `docs/runbook.md`.
