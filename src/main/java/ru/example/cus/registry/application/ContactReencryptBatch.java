package ru.example.cus.registry.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.example.cus.common.application.CryptoService;
import ru.example.cus.registry.domain.ContactNormalizer;
import ru.example.cus.registry.domain.SubjectContact;
import ru.example.cus.registry.infrastructure.SubjectContactRepository;

/**
 * Одна порция перешифрования в своей транзакции (NFR-3).
 *
 * <p>Отдельный бин, потому что {@code @Transactional} действует через прокси: вызов такого метода изнутри
 * того же объекта прошёл бы вообще без транзакции.
 */
@Component
public class ContactReencryptBatch {

    static final int SIZE = 500;

    private final SubjectContactRepository contacts;
    private final CryptoService crypto;

    public ContactReencryptBatch(SubjectContactRepository contacts, CryptoService crypto) {
        this.contacts = contacts;
        this.crypto = crypto;
    }

    @Transactional
    public long process(int page) {
        Page<SubjectContact> batch = contacts.findAllByOrderByIdAsc(PageRequest.of(page, SIZE));
        for (SubjectContact contact : batch) {
            // Чтение уже расшифровало значение, запись зашифрует его текущим ключом; HMAC пересчитывается.
            contact.applySearchHmac(
                    crypto.searchHmac(ContactNormalizer.normalize(contact.getType(), contact.getValue())));
            contacts.save(contact);
        }
        return batch.getNumberOfElements();
    }
}
