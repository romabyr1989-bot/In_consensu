package ru.example.inconsensu.common.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.example.inconsensu.common.domain.SignatureType;

/** §3: заглушка УКЭП отказывает честно, а не притворяется, что подпись проверена. */
class SignatureProviderTest {

    private final SignatureProvider provider = new UnavailableSignatureProvider();

    @Test
    void stub_declares_the_signature_type_it_stands_for() {
        assertThat(provider.supports()).isEqualTo(SignatureType.UKEP);
    }

    @Test
    void stub_rejects_instead_of_pretending_the_signature_is_valid() {
        SignatureProvider.Verification verification = provider.verify(Map.of("signatureRef", "ref"));

        assertThat(verification.valid()).isFalse();
        assertThat(verification.reason()).contains("не подключена");
    }
}
