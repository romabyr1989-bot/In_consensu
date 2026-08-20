package ru.example.inconsensu.common.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** §3: хранилище документов для dev отдаёт метаданные и не выпускает ссылку за пределы каталога. */
class DocumentStorageTest {

    @TempDir
    Path directory;

    @Test
    void describes_an_existing_document() throws IOException {
        Files.writeString(directory.resolve("scan.txt"), "заявление", StandardCharsets.UTF_8);
        DocumentStorage storage = new LocalFileDocumentStorage(directory.toString());

        assertThat(storage.exists("scan.txt")).isTrue();
        assertThat(storage.describe("scan.txt")).get().satisfies(info -> assertThat(info.name())
                .isEqualTo("scan.txt"));
    }

    /** Ссылку заполняет внешняя система: «../» не должно выводить за пределы каталога. */
    @Test
    void refuses_to_leave_the_configured_directory() throws IOException {
        Files.writeString(directory.resolve("scan.txt"), "заявление", StandardCharsets.UTF_8);
        DocumentStorage storage =
                new LocalFileDocumentStorage(directory.resolve("inner").toString());
        Files.createDirectories(directory.resolve("inner"));

        assertThat(storage.exists("../scan.txt")).isFalse();
        assertThat(storage.describe("../scan.txt")).isEmpty();
    }

    @Test
    void unknown_reference_is_simply_absent() {
        DocumentStorage storage = new LocalFileDocumentStorage(directory.toString());

        assertThat(storage.exists("нет-такого")).isFalse();
        assertThat(storage.exists(null)).isFalse();
    }
}
