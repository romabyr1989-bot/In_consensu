package ru.example.inconsensu.registry.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.example.inconsensu.common.application.CryptoService;

/**
 * Перешифрование контактов (NFR-3, ротация ключа).
 *
 * <p>Одна и та же операция решает две задачи: первичное шифрование уже накопленных данных при включении
 * флага и перевод записей на новый ключ. Чтение расшифровывает текущим ключом или предыдущим, запись всегда
 * шифрует текущим — значит, достаточно перечитать и сохранить.
 *
 * <p>Порциями и в отдельных транзакциях: на пяти миллионах субъектов (NFR-1) одна транзакция на всё
 * заблокировала бы таблицу и не поместилась бы в память.
 */
@Service
public class ContactMaintenanceService {

    private static final Logger LOG = LoggerFactory.getLogger(ContactMaintenanceService.class);

    /** @param processed сколько контактов перечитано и сохранено */
    public record ReencryptResult(long processed, boolean encryptionEnabled) {}

    private final ContactReencryptBatch batch;
    private final CryptoService crypto;

    public ContactMaintenanceService(ContactReencryptBatch batch, CryptoService crypto) {
        this.batch = batch;
        this.crypto = crypto;
    }

    public ReencryptResult reencryptAll() {
        long processed = 0;
        int page = 0;
        long done;
        while ((done = batch.process(page)) > 0) {
            processed += done;
            page++;
        }
        LOG.info("Перешифрование контактов завершено: обработано {}", processed);
        return new ReencryptResult(processed, crypto.isEnabled());
    }
}
