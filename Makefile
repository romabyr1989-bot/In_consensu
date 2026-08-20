# Commands are kept here so that CI (GitHub Actions today, GitLab CI later) stays a thin wrapper (§11).
SHELL := /bin/bash
# MVN_ARGS позволяет CI дописать флаги, не переписывая команды.
MVN_ARGS ?=
MVN   := ./mvnw -B -ntp $(MVN_ARGS)
BASE_URL ?= http://localhost:8080

# Приложение ставится на чистую операционную систему и работает с внешней PostgreSQL (ADR-0078).
DB_NAME ?= inconsensu
TEST_DB_NAME ?= inconsensu_test
DB_USER ?= inconsensu
DB_PASSWORD ?= inconsensu
DB_URL ?= jdbc:postgresql://localhost:5432/$(DB_NAME)

.DEFAULT_GOAL := help
.PHONY: help build test verify format lint openapi run db up package psql demo deps clean

help: ## Показать список команд
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

build: ## Собрать jar без тестов
	$(MVN) -DskipTests package

test: ## Юнит-тесты (без базы данных)
	$(MVN) test

verify: ## Полная проверка: юнит- и интеграционные тесты, покрытие, Spotless, Checkstyle (нужна PostgreSQL)
	$(MVN) verify

format: ## Отформатировать код (palantir-java-format)
	$(MVN) spotless:apply

lint: ## Только статические проверки
	$(MVN) spotless:check checkstyle:check

openapi: ## Перегенерировать docs/openapi.yaml из кода
	$(MVN) verify -Dinconsensu.openapi.update=true
	@echo "docs/openapi.yaml обновлён — не забудьте закоммитить"

run: ## Запустить приложение локально (профиль dev)
	$(MVN) spring-boot:run -Dspring-boot.run.profiles=dev

db: ## Создать базу и пользователя для локального запуска и тестов (нужна установленная PostgreSQL)
	psql -v ON_ERROR_STOP=1 -U postgres -c "CREATE USER $(DB_USER) WITH PASSWORD '$(DB_PASSWORD)';" || true
	psql -v ON_ERROR_STOP=1 -U postgres -c "CREATE DATABASE $(DB_NAME) OWNER $(DB_USER);" || true
	psql -v ON_ERROR_STOP=1 -U postgres -c "CREATE DATABASE $(TEST_DB_NAME) OWNER $(DB_USER);" || true

up: ## Запустить приложение локально с демо-данными (нужны PostgreSQL и SMTP)
	INCONSENSU_DB_URL=$(DB_URL) INCONSENSU_DB_USER=$(DB_USER) INCONSENSU_DB_PASSWORD=$(DB_PASSWORD) \
		$(MVN) spring-boot:run -Dspring-boot.run.profiles=demo
	@echo "API:      $(BASE_URL)"
	@echo "Swagger:  $(BASE_URL)/swagger-ui.html"

package: ## Собрать исполняемый JAR для установки на сервер
	$(MVN) -DskipTests package
	@echo "Готово: target/inconsensu.jar — установка описана в docs/install.md"

psql: ## Консоль PostgreSQL
	psql "$(subst jdbc:,,$(DB_URL))" -U $(DB_USER)

demo: ## Сквозной сценарий §11 через curl
	BASE_URL=$(BASE_URL) ./scripts/demo.sh

deps: ## Собрать артефакт для сканирования зависимостей (используется в CI)
	$(MVN) -DskipTests -Dspotless.check.skip=true -Dcheckstyle.skip=true package

clean: ## Очистить артефакты сборки
	$(MVN) clean
