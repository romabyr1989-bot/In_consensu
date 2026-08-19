package ru.example.cus.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import jakarta.persistence.Entity;
import java.util.List;
import java.util.stream.Stream;

/**
 * Executable form of the architecture rules of §5, checked by §11.
 *
 * <p>Rules that currently match no class are allowed (see {@code archunit.properties}); they start to bite as the
 * modules of stages 1..7 are implemented.
 */
@AnalyzeClasses(packages = ArchitectureTest.ROOT_PACKAGE, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String ROOT_PACKAGE = "ru.example.cus";

    private static final List<String> MODULES = List.of(
            "catalog", "registry", "thirdparty", "channels", "notification", "integration", "audit", "iam", "ui");

    @ArchTest
    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_API_OR_INFRASTRUCTURE = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..api..", "..infrastructure..")
            .because("§5: слой domain не зависит от api и infrastructure");

    @ArchTest
    static final ArchRule DTO_ARE_NOT_USED_AS_ENTITIES = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .beAnnotatedWith(Entity.class)
            .because("§11: DTO не используются как сущности");

    @ArchTest
    static final ArchRule NO_CONSOLE_OUTPUT =
            NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.because("§11: вывод только через slf4j");

    @ArchTest
    static final ArchRule NO_JAVA_UTIL_LOGGING =
            NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.because("NFR-6: единый JSON-лог через slf4j/logback");

    @ArchTest
    static final ArchRule MODULES_ARE_FREE_OF_CYCLES = SlicesRuleDefinition.slices()
            .matching("ru.example.cus.(*)..")
            .should()
            .beFreeOfCycles()
            .because("§5: модули общаются через application-сервисы и ApplicationEvent, а не циклами");

    /**
     * §5: между модулями допустимы только application-сервисы и доменные события.
     *
     * <p>Запрещены обращения к {@code infrastructure} чужого модуля (это его репозитории) и к его {@code api}
     * (контроллеры и DTO — точка входа для внешних клиентов, а не для соседнего модуля). Пакет {@code domain}
     * остаётся видимым намеренно: по §5 модули обмениваются доменными событиями, а они живут именно там.
     */
    @ArchTest
    static void modules_communicate_only_through_application_services(JavaClasses classes) {
        for (String module : MODULES) {
            String[] forbidden = MODULES.stream()
                    .filter(other -> !other.equals(module))
                    .flatMap(other -> Stream.of(
                            ROOT_PACKAGE + "." + other + ".infrastructure..", ROOT_PACKAGE + "." + other + ".api.."))
                    .toArray(String[]::new);

            noClasses()
                    .that()
                    .resideInAPackage(ROOT_PACKAGE + "." + module + "..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(forbidden)
                    .because("§5: модуль " + module + " не должен видеть infrastructure и api чужих модулей")
                    .check(classes);
        }
    }
}
