package ru.example.cus.registry.api;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.example.cus.registry.application.ContactMaintenanceService;
import ru.example.cus.registry.application.RetentionService;

/**
 * Эксплуатационные операции этапа 8 (NFR-3, NFR-5).
 *
 * <p>Обе операции выполняет администратор по процедуре из `docs/runbook.md`: включение шифрования на
 * заполненной базе и ротация ключа требуют перешифрования, а перенос «холодных» данных — осознанного
 * запуска, а не только расписания.
 */
@RestController
@RequestMapping("/api/v1/maintenance")
@PreAuthorize("hasRole('ADMIN')")
public class MaintenanceController {

    private final ContactMaintenanceService contacts;
    private final RetentionService retention;

    public MaintenanceController(ContactMaintenanceService contacts, RetentionService retention) {
        this.contacts = contacts;
        this.retention = retention;
    }

    @PostMapping("/contacts/reencrypt")
    @Operation(
            summary = "Перешифровать контакты",
            description = "Первичное шифрование при включении cus.crypto.enabled и ротация ключа (NFR-3)")
    public ContactMaintenanceService.ReencryptResult reencrypt() {
        return contacts.reencryptAll();
    }

    @PostMapping("/retention/run")
    @Operation(
            summary = "Прогон политики хранения",
            description = "dryRun=true только считает записи; иначе переносит отозванные согласия в архив (NFR-5)")
    public RetentionService.RetentionResult retention(@RequestParam(defaultValue = "true") boolean dryRun) {
        return retention.run(dryRun);
    }
}
