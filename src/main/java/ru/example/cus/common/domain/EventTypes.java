package ru.example.cus.common.domain;

/** Типы событий для outbox и webhooks (FR-9.4). Имена — часть контракта с потребителями (§14.9). */
public final class EventTypes {

    public static final String CONSENT_GRANTED = "consent.granted";
    public static final String CONSENT_REVOKED = "consent.revoked";
    public static final String CONSENT_SUPERSEDED = "consent.superseded";
    public static final String CONSENT_EXPIRING = "consent.expiring";
    public static final String CONSENT_EXPIRED = "consent.expired";
    public static final String FORM_PUBLISHED = "form.published";
    public static final String THIRD_PARTY_CONTRACT_EXPIRING = "third_party.contract_expiring";
    public static final String IMPORT_FINISHED = "import.finished";

    private EventTypes() {}
}
