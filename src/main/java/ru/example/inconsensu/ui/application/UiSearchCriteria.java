package ru.example.inconsensu.ui.application;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Критерий поиска клиента, сохранённый на сервере (UI-0.10).
 *
 * <p>UI-0.10 запрещает выносить ПДн в URL, а поиск ведётся как раз по телефону, email или ФИО. Поэтому
 * форма отправляется методом POST, значение остаётся в сессии, а в адресную строку попадает только
 * идентификатор запроса — его же используют ссылки пагинации.
 *
 * <p>Хранится ограниченное число последних запросов: сессия живёт до получаса (UI-0.3), и без предела
 * список рос бы на каждый поиск.
 */
@Service
public class UiSearchCriteria {

    static final String SESSION_KEY = "ui.search.criteria";
    private static final int KEEP_LAST = 10;

    /** Сохраняет запрос и возвращает идентификатор для адресной строки. */
    public UUID remember(HttpSession session, String query) {
        UUID id = UUID.randomUUID();
        store(session).put(id.toString(), query);
        return id;
    }

    /** Возвращает сохранённый запрос или {@code null}, если идентификатор неизвестен или устарел. */
    public String recall(HttpSession session, UUID id) {
        return id == null ? null : store(session).get(id.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> store(HttpSession session) {
        Object existing = session.getAttribute(SESSION_KEY);
        if (existing instanceof Map) {
            return (Map<String, String>) existing;
        }
        Map<String, String> created = new BoundedSearchStore();
        session.setAttribute(SESSION_KEY, created);
        return created;
    }

    /** Сессия сериализуется вместе с содержимым, поэтому хранилище объявлено явным классом. */
    private static final class BoundedSearchStore extends LinkedHashMap<String, String> implements Serializable {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > KEEP_LAST;
        }
    }
}
