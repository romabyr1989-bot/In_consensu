package ru.example.inconsensu.common.application;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.inconsensu.common.domain.PdnCategory;
import ru.example.inconsensu.common.infrastructure.PdnCategoryRepository;

/**
 * Справочник категорий персональных данных (§6, FR-11.4).
 *
 * <p>Точка входа для соседних модулей: по §5 они обращаются к application-сервисам, а репозиторий каталога
 * остаётся его внутренним делом.
 */
@Service
public class PdnCategoryService {

    private final PdnCategoryRepository repository;

    public PdnCategoryService(PdnCategoryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<PdnCategory> activeCategories() {
        return repository.findByActiveTrueOrderBySortOrderAsc();
    }

    /** Все ли коды существуют в справочнике: используется валидацией передач и форм. */
    @Transactional(readOnly = true)
    public boolean allExist(Set<String> codes) {
        return codes == null
                || codes.isEmpty()
                || repository.findByCodeIn(codes).size() == codes.size();
    }

    /**
     * Есть ли среди кодов специальная или биометрическая категория (ст. 10, 11 152-ФЗ).
     *
     * <p>Нужно валидатору форм: смешивание таких категорий с обычными в одном пункте — повод для предупреждения
     * (FR-1.4).
     */
    /**
     * Смешаны ли в одном перечне специальные (или биометрические) категории с обычными.
     *
     * <p>Именно это предупреждает FR-1.4: «специальные категории включены в общий пункт». Вынесенные в
     * отдельный, чистый пункт специальные категории — правильное оформление, и ругаться на него нельзя.
     */
    @Transactional(readOnly = true)
    public boolean mixesSpecialWithOrdinary(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return false;
        }
        var categories = repository.findByCodeIn(Set.copyOf(codes));
        boolean special = categories.stream().anyMatch(category -> category.isSpecial() || category.isBiometric());
        boolean ordinary = categories.stream().anyMatch(category -> !category.isSpecial() && !category.isBiometric());
        return special && ordinary;
    }

    @Transactional(readOnly = true)
    public boolean anySpecial(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return false;
        }
        return repository.findByCodeIn(Set.copyOf(codes)).stream()
                .anyMatch(category -> category.isSpecial() || category.isBiometric());
    }
}
