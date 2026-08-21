package ru.example.inconsensu.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * §12, NFR-3: переменные окружения из документации действительно читаются приложением.
 *
 * <p>Spring связывает переменную с настройкой по её полному пути, поэтому `INCONSENSU_JWT_SECRET`
 * попадает в `inconsensu.jwt.secret`, а не в `inconsensu.security.jwt.secret`, — и установка по
 * инструкции получала случайный ключ подписи и ни одного администратора, сообщая об этом только строкой в
 * логе. Короткие имена работают лишь потому, что названы в `application.yml`; этот тест сторожит, чтобы
 * документация и конфигурация не разъезжались снова.
 */
class InstallEnvironmentTest {

    /** Документы, где переменные адресованы администратору установки. */
    private static final List<String> DOCUMENTS = List.of("docs/install.md", "README.md", "docs/runbook.md");

    /** Переменные тестового окружения: их читает сборка, а не приложение (ADR-0078). */
    private static final Set<String> BUILD_ONLY =
            Set.of("INCONSENSU_TEST_DB_URL", "INCONSENSU_TEST_DB_USER", "INCONSENSU_TEST_DB_PASSWORD");

    private static final Pattern VARIABLE = Pattern.compile("\\bINCONSENSU_[A-Z0-9_]+");

    @Test
    void every_documented_variable_is_bound_in_the_configuration() throws IOException {
        String configuration = configuration();

        for (String document : DOCUMENTS) {
            Set<String> undocumented = new LinkedHashSet<>();
            for (String variable : variablesOf(Path.of(document))) {
                if (!BUILD_ONLY.contains(variable) && !configuration.contains(variable)) {
                    undocumented.add(variable);
                }
            }
            assertThat(undocumented)
                    .as("%s называет переменные, которых нет ни в одном application*.yml", document)
                    .isEmpty();
        }
    }

    private static Set<String> variablesOf(Path document) throws IOException {
        Matcher matcher = VARIABLE.matcher(Files.readString(document, StandardCharsets.UTF_8));
        Set<String> variables = new LinkedHashSet<>();
        while (matcher.find()) {
            variables.add(matcher.group());
        }
        return variables;
    }

    private static String configuration() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources"))) {
            StringBuilder text = new StringBuilder();
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("application"))
                    .toList()) {
                text.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
            return text.toString();
        }
    }
}
