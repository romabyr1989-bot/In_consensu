package ru.example.inconsensu.catalog.api;

import java.util.List;
import java.util.UUID;
import ru.example.inconsensu.catalog.domain.ConsentType;

/** Тип согласия в ответе API (FR-1.1, UI-6). */
public record ConsentTypeResponse(
        UUID id,
        String code,
        String nameRu,
        String description,
        String category,
        String categoryRu,
        List<String> channels,
        boolean requiresThirdParty,
        String defaultValidity,
        String dependsOnCode,
        boolean businessSignificant,
        boolean active,
        int sortOrder) {

    public static ConsentTypeResponse of(ConsentType type) {
        return new ConsentTypeResponse(
                type.getId(),
                type.getCode(),
                type.getNameRu(),
                type.getDescription(),
                type.getCategory().name(),
                type.getCategory().nameRu(),
                type.getChannels().stream().map(Enum::name).toList(),
                type.isRequiresThirdParty(),
                type.getDefaultValidity(),
                type.getDependsOn() == null ? null : type.getDependsOn().getCode(),
                type.isBusinessSignificant(),
                type.isActive(),
                type.getSortOrder());
    }
}
