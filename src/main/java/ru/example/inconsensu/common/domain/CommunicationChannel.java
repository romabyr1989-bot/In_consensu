package ru.example.inconsensu.common.domain;

/** Каналы коммуникации (Приложение D). */
public enum CommunicationChannel {
    PHONE_CALL("Телефонный звонок"),
    SMS("SMS"),
    EMAIL("Электронная почта"),
    PUSH("Push-уведомление"),
    MESSENGER("Мессенджер"),
    POSTAL_MAIL("Почтовая рассылка");

    private final String nameRu;

    CommunicationChannel(String nameRu) {
        this.nameRu = nameRu;
    }

    /** User facing name; dictionaries are served in Russian (NFR-8, FR-11.4). */
    public String nameRu() {
        return nameRu;
    }
}
