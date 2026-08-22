package ru.example.inconsensu.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.example.inconsensu.common.error.ErrorCode;

/**
 * Формат ошибок в опубликованном контракте (§9, NFR-7).
 *
 * <p>Сервис отвечает на любой отказ телом RFC 9457 (`ProblemDetail`) с полем `type` вида
 * `urn:inconsensu:error:<код>`, но в спецификации об этом не было ни слова: клиент узнавал о формате
 * только получив первую ошибку. Здесь схема объявляется один раз и подставляется в каждую операцию.
 *
 * <p>Коды перечисляются не руками, а из {@link ErrorCode}: список в документации не должен расходиться с
 * тем, что действительно отдаёт сервис.
 */
@Configuration
public class OpenApiErrorsCustomizer {

    private static final String SCHEMA_NAME = "ProblemDetail";
    private static final String SCHEMA_REF = "#/components/schemas/" + SCHEMA_NAME;

    @Bean
    public OpenApiCustomizer problemDetailResponses() {
        return openApi -> {
            components(openApi).addSchemas(SCHEMA_NAME, problemDetail());
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(this::describeErrors));
        };
    }

    private static Components components(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        return openApi.getComponents();
    }

    /**
     * Ответы, возможные у любой операции машинной цепочки.
     *
     * <p>404 добавляется только там, где в адресе есть идентификатор: у списков его быть не может, и обещать
     * его в контракте — врать.
     */
    private void describeErrors(Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            return;
        }
        add(responses, "400", "Запрос не прошёл валидацию");
        add(responses, "401", "Токен отсутствует, истёк или не принят");
        add(responses, "403", "У роли нет прав на операцию");
        if (hasPathParameter(operation)) {
            add(responses, "404", "Объект не найден");
        }
        add(responses, "500", "Внутренняя ошибка: в теле только код обращения, без технических деталей");
    }

    private static boolean hasPathParameter(Operation operation) {
        List<Parameter> parameters = operation.getParameters();
        return parameters != null && parameters.stream().anyMatch(parameter -> "path".equals(parameter.getIn()));
    }

    private static void add(ApiResponses responses, String status, String description) {
        if (responses.containsKey(status)) {
            return;
        }
        responses.addApiResponse(
                status,
                new ApiResponse()
                        .description(description)
                        .content(new Content()
                                .addMediaType(
                                        "application/problem+json",
                                        new MediaType().schema(new Schema<>().$ref(SCHEMA_REF)))));
    }

    private static Schema<?> problemDetail() {
        String codes = Arrays.stream(ErrorCode.values())
                .map(code -> code.type().toString())
                .collect(Collectors.joining(", "));

        ObjectSchema schema = new ObjectSchema();
        schema.description("Отказ по RFC 9457. Возможные значения type: " + codes);
        schema.addProperty("type", new StringSchema().description("Код отказа: urn:inconsensu:error:<код>"));
        schema.addProperty("title", new StringSchema().description("Краткое название отказа по-русски"));
        schema.addProperty("status", new Schema<Integer>().type("integer").format("int32"));
        schema.addProperty("detail", new StringSchema().description("Пояснение без технических деталей и без ПДн"));
        schema.addProperty("instance", new StringSchema().description("Адрес, по которому произошёл отказ"));
        schema.addProperty("timestamp", new StringSchema().format("date-time").description("Момент отказа"));
        schema.addProperty(
                "requestId",
                new StringSchema().description("Сквозной идентификатор запроса: по нему отказ ищется в журнале"));

        ObjectSchema error = new ObjectSchema();
        error.addProperty("field", new StringSchema().description("Поле запроса, которое не принято"));
        error.addProperty("message", new StringSchema().description("Что именно не так с полем"));
        schema.addProperty(
                "errors",
                new ArraySchema().items(error).description("Заполняется при отказе валидации (FR-4.2, UI-0.9)"));
        return schema;
    }
}
