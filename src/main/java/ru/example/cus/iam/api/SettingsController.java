package ru.example.cus.iam.api;

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.example.cus.iam.application.OperatorSettingsService;

/** §9, FR-11.3: настройки оператора. Чтение и изменение — ADMIN и DPO. */
@RestController
@RequestMapping("/api/v1/settings")
@PreAuthorize("hasAnyRole('ADMIN','DPO')")
public class SettingsController {

    public record UpdateSettingsRequest(@NotEmpty Map<String, String> settings) {}

    private final OperatorSettingsService service;

    public SettingsController(OperatorSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, String> get() {
        return service.all();
    }

    @PutMapping
    public Map<String, String> update(@RequestBody UpdateSettingsRequest request) {
        return service.update(request.settings());
    }
}
