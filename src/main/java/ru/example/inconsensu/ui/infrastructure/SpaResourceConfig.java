package ru.example.inconsensu.ui.infrastructure;

import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Раздача одностраничного приложения из артефакта (ADR-0087, UI-0.2).
 *
 * <p>Собранная статика лежит в самом JAR: продукт ставится на чистую операционную систему, и отдельный
 * веб-сервер для фронта не требуется — иначе установка перестала бы быть однокомандной.
 *
 * <p>Любой путь внутри `/app/**`, которому не соответствует файл, отдаёт `index.html`: маршрутизацией
 * занимается само приложение, а перезагрузка страницы по внутреннему адресу не должна давать 404.
 */
@Component
public class SpaResourceConfig implements WebMvcConfigurer {

    /**
     * Корень приложения.
     *
     * <p>Для `/app/` остаток пути пуст, и обработчик статики до резолвера не доходит — заход на корень давал
     * 404. Проще и надёжнее назвать оболочку явным правилом, чем угадывать поведение цепочки ресурсов.
     */
    @Override
    public void addViewControllers(org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
        registry.addViewController("/app").setViewName("forward:/app/index.html");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
    }

    private static final String SPA_ROOT = "classpath:/static/app/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/app/**")
                .addResourceLocations(SPA_ROOT)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // Пустой путь — это заход на /app/: отдаём оболочку, иначе корень приложения даёт 404.
                        if (!resourcePath.isBlank()) {
                            Resource requested = location.createRelative(resourcePath);
                            if (requested.exists() && requested.isReadable()) {
                                return requested;
                            }
                        }
                        // Внутренний маршрут приложения: отдаём оболочку, дальше разбирается роутер.
                        ClassPathResource shell = new ClassPathResource("static/app/index.html");
                        return shell.exists() ? shell : null;
                    }
                });
    }
}
