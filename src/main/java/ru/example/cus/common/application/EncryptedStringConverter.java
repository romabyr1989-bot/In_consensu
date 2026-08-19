package ru.example.cus.common.application;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Шифрование строкового поля при записи в базу (NFR-3).
 *
 * <p>Конвертер — бин Spring: Boot регистрирует {@code SpringBeanContainer} в Hibernate, поэтому сюда можно
 * внедрить {@link CryptoService}, а не тянуть ключ через статику.
 *
 * <p>Расшифровка выполняется всегда, независимо от флага: после его выключения в базе остаются
 * зашифрованные значения, и читать их всё равно нужно.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final CryptoService crypto;

    public EncryptedStringConverter(CryptoService crypto) {
        this.crypto = crypto;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return crypto.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return crypto.decrypt(dbData);
    }
}
