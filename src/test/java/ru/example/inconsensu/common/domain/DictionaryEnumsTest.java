package ru.example.inconsensu.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * NFR-8 и FR-11.4: справочники отдаются в UI с русскими названиями, поэтому пустых названий быть не должно ни у
 * одного значения перечислений Приложения D.
 */
class DictionaryEnumsTest {

    static Stream<Enum<?>> allDictionaryValues() {
        return Stream.of(
                        CommunicationChannel.values(),
                        ConsentCategory.values(),
                        ConsentSource.values(),
                        ConsentStatus.values(),
                        FormStatus.values(),
                        SignatureType.values(),
                        RevocationSource.values(),
                        ThirdPartyRole.values(),
                        ActorType.values(),
                        ChannelDenyReason.values(),
                        ContactType.values(),
                        RoleCode.values(),
                        AuditEventType.values())
                .flatMap(Stream::of);
    }

    @ParameterizedTest
    @MethodSource("allDictionaryValues")
    void every_value_has_a_russian_name(Enum<?> value) {
        String nameRu = nameRu(value);

        assertThat(nameRu)
                .as("русское название для %s.%s", value.getClass().getSimpleName(), value.name())
                .isNotBlank();
    }

    @Test
    void enumerations_match_appendix_d() {
        assertThat(List.of(CommunicationChannel.values()))
                .containsExactly(
                        CommunicationChannel.PHONE_CALL,
                        CommunicationChannel.SMS,
                        CommunicationChannel.EMAIL,
                        CommunicationChannel.PUSH,
                        CommunicationChannel.MESSENGER,
                        CommunicationChannel.POSTAL_MAIL);
        assertThat(List.of(ConsentStatus.values()))
                .containsExactly(
                        ConsentStatus.ACTIVE,
                        ConsentStatus.EXPIRING,
                        ConsentStatus.EXPIRED,
                        ConsentStatus.REVOKED,
                        ConsentStatus.SUPERSEDED);
        assertThat(List.of(RoleCode.values())).hasSize(7);
        assertThat(List.of(AuditEventType.values())).hasSize(18);
    }

    private static String nameRu(Enum<?> value) {
        try {
            return (String) value.getClass().getMethod("nameRu").invoke(value);
        } catch (Exception e) {
            throw new AssertionError("У перечисления нет метода nameRu(): " + value.getClass(), e);
        }
    }
}
