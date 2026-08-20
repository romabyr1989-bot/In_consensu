package ru.example.inconsensu.common.integration;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import ru.example.inconsensu.common.domain.SignatureType;

/**
 * Заглушка проверки УКЭП (§3).
 *
 * <p>Криптопровайдера в поставке нет, и притворяться, что подпись проверена, нельзя: доказательство,
 * которое никто не проверял, хуже отсутствующего. Поэтому заглушка честно отказывает, а реальная
 * реализация подменяет её своим бином.
 */
@Component
@ConditionalOnMissingBean(name = "qualifiedSignatureProvider")
public class UnavailableSignatureProvider implements SignatureProvider {

    @Override
    public SignatureType supports() {
        return SignatureType.UKEP;
    }

    @Override
    public Verification verify(Map<String, Object> evidence) {
        return Verification.rejected("Проверка квалифицированной подписи не подключена: криптопровайдер вне поставки");
    }
}
