# Commands are kept here so that CI (GitHub Actions today, GitLab CI later) stays a thin wrapper (§11).
SHELL := /bin/bash
# MVN_ARGS позволяет CI дописать флаги, не переписывая команды: например версию Docker API,
# которую поддерживает движок конкретного раннера (см. inconsensu.docker.api-version в pom.xml).
MVN_ARGS ?=
MVN   := ./mvnw -B -ntp $(MVN_ARGS)
BASE_URL ?= http://localhost:8080

.DEFAULT_GOAL := help
.PHONY: help build test verify format lint openapi run up down restart logs psql mail demo deps docker-build clean

help: ## Показать список команд
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

build: ## Собрать jar без тестов
	$(MVN) -DskipTests package

test: ## Юнит-тесты (без Testcontainers)
	$(MVN) test

verify: ## Полная проверка: юнит- и интеграционные тесты, покрытие, Spotless, Checkstyle (нужен Docker)
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

up: ## Поднять окружение целиком (app, postgres, mailpit)
	docker compose up -d --build
	@echo "API:      $(BASE_URL)"
	@echo "Swagger:  $(BASE_URL)/swagger-ui.html"
	@echo "Mailpit:  http://localhost:8025"

down: ## Остановить окружение
	docker compose down

restart: down up ## Перезапустить окружение

logs: ## Логи приложения
	docker compose logs -f app

psql: ## Консоль PostgreSQL
	docker compose exec postgres psql -U inconsensu -d inconsensu

mail: ## Открыть Mailpit
	@echo "http://localhost:8025"

demo: ## Сквозной сценарий §11 через curl
	BASE_URL=$(BASE_URL) ./scripts/demo.sh

docker-build: ## Собрать Docker-образ приложения (нужен BuildKit)
	DOCKER_BUILDKIT=1 docker build -t inconsensu:local .

deps: ## Собрать артефакт для сканирования зависимостей (используется в CI)
	$(MVN) -DskipTests -Dspotless.check.skip=true -Dcheckstyle.skip=true package

clean: ## Очистить артефакты сборки
	$(MVN) clean
