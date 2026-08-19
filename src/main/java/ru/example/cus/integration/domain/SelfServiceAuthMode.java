package ru.example.cus.integration.domain;

/** Режим аутентификации самообслуживания (FR-8.1, настройка {@code cus.selfservice.auth-mode}). */
public enum SelfServiceAuthMode {
    /** Клиент приходит с JWT внешнего IdP, в котором есть claim {@code subject_external_id}. */
    SUBJECT_JWT,
    /** Приходит личный кабинет с сервисным токеном и передаёт внешний идентификатор клиента. */
    SERVICE_TOKEN
}
