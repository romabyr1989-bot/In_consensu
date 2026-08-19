package ru.example.cus.thirdparty.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.example.cus.common.domain.ConsentStatus;

/**
 * Значения, на которых считается разрешение передачи (§7.7).
 *
 * <p>Домен не ходит в репозитории (§5): согласия приносит модуль registry через порт, реквизиты третьего
 * лица — сам модуль thirdparty.
 */
public final class TransferSnapshots {

    private TransferSnapshots() {}

    /** Согласие субъекта на передачу конкретному третьему лицу. */
    public record TransferConsent(
            UUID consentId, UUID thirdPartyId, List<String> pdnCategories, ConsentStatus status, Instant validUntil) {

        public boolean isUsable() {
            return status == ConsentStatus.ACTIVE || status == ConsentStatus.EXPIRING;
        }
    }

    /** Третье лицо в том виде, в каком его видит расчёт передачи. */
    public record Recipient(
            UUID id,
            String name,
            String role,
            List<String> allowedPdnCategories,
            Instant contractValidUntil,
            boolean active) {}
}
