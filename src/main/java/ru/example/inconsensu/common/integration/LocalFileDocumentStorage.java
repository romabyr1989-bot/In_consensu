package ru.example.inconsensu.common.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Реализация хранилища документов на локальной файловой системе (§3, для dev).
 *
 * <p>Каталог задаётся настройкой `inconsensu.documents.directory`. Ссылка трактуется как имя файла внутри
 * него и нормализуется: значение приходит из доказательств, а те заполняет внешняя система, поэтому
 * «../» в ссылке не должно выводить за пределы каталога.
 */
@Component
@ConditionalOnMissingBean(name = "externalDocumentStorage")
public class LocalFileDocumentStorage implements DocumentStorage {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFileDocumentStorage.class);

    private final Path root;

    public LocalFileDocumentStorage(@Value("${inconsensu.documents.directory:documents}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public boolean exists(String reference) {
        return resolve(reference).map(Files::isRegularFile).orElse(false);
    }

    @Override
    public Optional<DocumentInfo> describe(String reference) {
        return resolve(reference).filter(Files::isRegularFile).map(path -> {
            try {
                return new DocumentInfo(
                        reference, path.getFileName().toString(), Files.size(path), Files.probeContentType(path));
            } catch (IOException e) {
                LOG.warn("Не удалось прочитать метаданные документа: {}", e.getMessage());
                return null;
            }
        });
    }

    /** Пустой результат означает выход за пределы каталога — такую ссылку читать нельзя. */
    private Optional<Path> resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        Path candidate = root.resolve(reference).normalize();
        return candidate.startsWith(root) ? Optional.of(candidate) : Optional.empty();
    }
}
