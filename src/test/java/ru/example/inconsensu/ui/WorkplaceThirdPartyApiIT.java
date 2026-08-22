package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.common.domain.ThirdPartyRole;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.support.TestForms;
import ru.example.inconsensu.thirdparty.application.PartnerExportService;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/**
 * Приёмка UI-11 на слое рабочего места: справочник партнёров, состав выгрузки и права на реквизиты.
 *
 * <p>Проверяется ответ сервера одностраничному приложению, а не разметка: срок договора, русские названия
 * категорий ПДн и состав будущего файла считает сервер, он же решает, кому правка справочника разрешена.
 * Спрятанной кнопки для этого мало — отказ обязан прийти кодом ответа.
 *
 * <p>Прежние проверки ходили по страницам Thymeleaf ({@code UiThirdPartyIT}); правила от смены интерфейса
 * не изменились и переносятся сюда.
 */
@AutoConfigureMockMvc
class WorkplaceThirdPartyApiIT extends AbstractIntegrationTest {

    private static final String PARTIES = "/ui/api/third-parties";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private TestForms testForms;

    @Autowired
    private ThirdPartyService thirdParties;

    @Autowired
    private PartnerExportService exports;

    @Autowired
    private ConsentRegistrationService registration;

    @Test
    void directory_names_the_contract_term_and_the_categories_in_russian() throws Exception {
        ThirdParty party = createParty(LocalDate.now().plusDays(10));

        String directory = mockMvc.perform(get(PARTIES).session(loginAs(RoleCode.MANAGER.name())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode row = rowOf(directory, party.getId());
        assertThat(row.path("name").asText()).isEqualTo(party.getName());
        // UI-11: в строке видны и сам срок договора, и предупреждение о его окончании — одного бейджа мало.
        assertThat(row.path("contractNumber").asText()).isEqualTo(party.getContractNumber());
        assertThat(row.path("contractUntil").asText()).isEqualTo(formatted(party.getContractValidUntil()));
        assertThat(row.path("contractBadge").asText()).startsWith("истекает через");
        // UI-0.4: категории ПДн названы по-русски, а не кодами справочника.
        assertThat(row.path("categoriesRu").asText())
                .contains("Фамилия, имя, отчество")
                .contains("Номер телефона")
                .doesNotContain("FIO");
    }

    @Test
    void card_names_what_goes_into_the_export_before_it_is_created() throws Exception {
        ThirdParty party = createParty(LocalDate.now().plusYears(1));
        registerTransferConsent(party.getId());

        JsonNode card = cardOf(loginAs(RoleCode.DPO.name()), party.getId());

        // UI-11: сколько записей и какие категории уйдут партнёру, известно до того, как файл создан.
        assertThat(card.path("exportRecords").asLong()).isPositive();
        assertThat(textsOf(card.path("exportCategories"))).containsExactlyInAnyOrder("FIO", "PHONE");
        assertThat(card.path("exportAllowed").asBoolean()).isTrue();
        assertThat(card.path("allowedCategoriesRu").asText())
                .contains("Фамилия, имя, отчество")
                .contains("Номер телефона");
        // Выгрузок ещё не было: состав показан по договору и согласиям, а не по прошлому файлу.
        assertThat(card.path("exports").size()).isZero();
    }

    @Test
    void export_is_created_and_downloaded_through_the_interface_layer() throws Exception {
        ThirdParty party = createParty(LocalDate.now().plusYears(1));
        registerTransferConsent(party.getId());
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());

        String created = mockMvc.perform(post(PARTIES + "/" + party.getId() + "/exports")
                        .session(dpo)
                        .with(csrf())
                        .param("format", "csv"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode answer = MAPPER.readTree(created);
        assertThat(answer.path("message").asText()).startsWith("Выгрузка сформирована");
        UUID exportId = UUID.fromString(answer.path("exports").get(0).path("id").asText());

        var response = mockMvc.perform(
                        get(PARTIES + "/exports/" + exportId + "/download").session(dpo))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        // UI-11: файл отдаётся тем же слоем, что и карточка, и приходит вложением. Машинная цепочка §12
        // требует JWT, и браузер с сессионной кукой получал бы там 401 — кнопка «Скачать» не работала бы.
        assertThat(response.getHeader("Content-Disposition"))
                .startsWith("attachment;")
                .contains("export-" + exportId + ".csv");
        // Имя файла и адрес состоят из идентификаторов: ПДн не попадают ни в журнал веб-сервера, ни в папку
        // загрузок сотрудника (NFR-3, UI-0.10).
        assertThat(response.getContentAsString()).startsWith("external_id");

        // FR-7.4: выгрузка остаётся в журнале с контрольной суммой — через год нужно показать, что ушло.
        var stored = exports.listFor(party.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getRecordsCount()).isPositive();
        assertThat(stored.get(0).getFileChecksum()).startsWith("sha256:");
    }

    @Test
    void editing_requisites_keeps_the_fields_the_form_does_not_ask_about() throws Exception {
        ThirdParty party = createParty(LocalDate.now().plusYears(1));
        MockHttpSession lawyer = loginAs(RoleCode.LAWYER.name());

        // Правка уходит тем составом полей, который отдала карточка: реквизита, которого в ней нет,
        // приложению неоткуда взять — оно пришлёт пустое значение и затрёт его в справочнике.
        JsonNode card = cardOf(lawyer, party.getId());

        mockMvc.perform(post(PARTIES)
                        .session(lawyer)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(card, "ООО «Моменто» (новое имя)")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ООО «Моменто» (новое имя)"));

        ThirdParty saved = thirdParties.get(party.getId());
        assertThat(saved.getName()).isEqualTo("ООО «Моменто» (новое имя)");
        assertThat(saved.getShortName()).isEqualTo(party.getShortName());
        assertThat(saved.getOgrn()).isEqualTo(party.getOgrn());
        assertThat(saved.getContractDate()).isEqualTo(party.getContractDate());
    }

    @Test
    void requisites_are_readable_by_a_role_without_the_right_to_edit_them() throws Exception {
        ThirdParty party = createParty(LocalDate.now().plusYears(1));
        MockHttpSession manager = loginAs(RoleCode.MANAGER.name());

        // §16.2: справочник читают все роли рабочего места — раньше карточка была закрыта целиком.
        JsonNode card = cardOf(manager, party.getId());
        assertThat(card.path("inn").asText()).isEqualTo(party.getInn());
        assertThat(card.path("contractNumber").asText()).isEqualTo(party.getContractNumber());

        // Приложение E: реквизиты правят юрист, ответственный за ПДн и администратор.
        String denied = mockMvc.perform(post(PARTIES)
                        .session(manager)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveRequest(card, "ООО «Моменто» (правка без прав)")))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode problem = MAPPER.readTree(denied);
        assertThat(problem.path("status").asInt()).isEqualTo(403);
        assertThat(problem.path("type").asText()).startsWith("urn:inconsensu:error:");
        assertThat(thirdParties.get(party.getId()).getName()).isEqualTo(party.getName());
    }

    @Test
    void rejected_field_is_named_in_the_error_body() throws Exception {
        ThirdParty existing = createParty(LocalDate.now().plusYears(1));
        MockHttpSession lawyer = loginAs(RoleCode.LAWYER.name());

        // Повторный ИНН: справочник его не примет, и отказ обязан назвать поле.
        String body =
                """
                {"inn":"%s","name":"ООО «Ошибка»","address":"Москва, ул. Тестовая, 2","role":"PROCESSOR"}
                """
                        .formatted(existing.getInn());

        String problem = mockMvc.perform(post(PARTIES)
                        .session(lawyer)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:inconsensu:error:validation-failed"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // UI-0.9: подпись об ошибке ставится под своим полем, и его имя приходит с сервера. Без него
        // приложению остаётся только сводка сверху, а сотрудник ищет ошибку глазами.
        assertThat(rejectedFields(problem)).contains("inn");
    }

    /** Карточка глазами приложения: дальше проверяется именно то, что оно получило от сервера. */
    private JsonNode cardOf(MockHttpSession session, UUID partyId) throws Exception {
        String body = mockMvc.perform(get(PARTIES + "/" + partyId).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return MAPPER.readTree(body);
    }

    /**
     * Тело сохранения, собранное из карточки: так его собирает и приложение.
     *
     * <p>Каждый реквизит берётся именно из ответа сервера и требуется непустым — иначе правка имени тихо
     * затирала бы поля, о которых форма не спрашивала.
     */
    private static String saveRequest(JsonNode card, String name) {
        return """
                {"id":"%s","name":"%s","shortName":"%s","ogrn":"%s","address":"%s","role":"%s",
                 "contractNumber":"%s","contractDate":"%s","contractValidUntil":"%s",
                 "allowedPdnCategories":%s,"contactEmail":"%s"}
                """
                .formatted(
                        required(card, "id"),
                        name,
                        required(card, "shortName"),
                        required(card, "ogrn"),
                        required(card, "address"),
                        required(card, "role"),
                        required(card, "contractNumber"),
                        required(card, "contractDate"),
                        required(card, "contractValidUntil"),
                        card.path("allowedPdnCategories"),
                        required(card, "contactEmail"));
    }

    private static String required(JsonNode card, String field) {
        assertThat(card.has(field))
                .as("карточка не отдала реквизит «%s»", field)
                .isTrue();
        String value = card.path(field).asText();
        assertThat(value).as("реквизит «%s» пуст", field).isNotBlank();
        return value;
    }

    /** Строка справочника: база одна на прогон, поэтому партнёр ищется по идентификатору, а не по номеру. */
    private static JsonNode rowOf(String json, UUID partyId) throws Exception {
        for (JsonNode row : MAPPER.readTree(json)) {
            if (partyId.toString().equals(row.path("id").asText())) {
                return row;
            }
        }
        throw new AssertionError("В справочнике нет третьего лица " + partyId);
    }

    /** Разбор массива Jackson-ом: фильтры JsonPath по значению поля в этом проекте не работают. */
    private static List<String> textsOf(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.asText()));
        return values;
    }

    private static List<String> rejectedFields(String json) throws Exception {
        JsonNode problem = MAPPER.readTree(json);
        assertThat(problem.has("errors"))
                .as("в теле отказа нет перечня отклонённых полей")
                .isTrue();
        List<String> fields = new ArrayList<>();
        problem.path("errors").forEach(error -> fields.add(error.path("field").asText()));
        return fields;
    }

    private static String formatted(LocalDate date) {
        return "%02d.%02d.%d".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    private ThirdParty createParty(LocalDate contractValidUntil) {
        String inn = String.valueOf(7_700_000_000L + Math.abs(UUID.randomUUID().hashCode() % 1_000_000));
        return RunAs.roles(
                "test-admin",
                List.of("ADMIN"),
                () -> thirdParties.create(
                        inn,
                        new ThirdPartyService.ThirdPartyForm(
                                "ООО «Моменто» " + inn,
                                "Моменто",
                                "1027700132195",
                                "Москва, ул. Тестовая, 1",
                                ThirdPartyRole.PROCESSOR,
                                "ДС-2025/117",
                                LocalDate.of(2025, 9, 1),
                                contractValidUntil,
                                Set.of("FIO", "PHONE"),
                                "dpo@momento.example")));
    }

    /** Согласие на передачу этому партнёру: без него состав выгрузки был бы пуст и правило не проверялось. */
    private void registerTransferConsent(UUID thirdPartyId) {
        ConsentForm form = testForms.publishFormWithTransfer(thirdPartyId, List.of("FIO", "PHONE"));
        List<ConsentRegistrationService.ItemDecision> items = form.getItems().stream()
                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                .toList();

        SubjectService.SubjectForm subject = new SubjectService.SubjectForm(
                "CRM-TP-" + UUID.randomUUID().toString().substring(0, 8),
                "Травин",
                "Иван",
                "Сергеевич",
                null,
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-45", true),
                        new SubjectService.ContactForm(
                                ContactType.EMAIL,
                                "travin-tp-" + UUID.randomUUID().toString().substring(0, 6) + "@example.ru",
                                true)));

        registration.register(
                UUID.randomUUID().toString(),
                new ConsentRegistrationService.RegistrationRequest(
                        null,
                        subject,
                        form.getId(),
                        items,
                        Instant.now(),
                        ConsentSource.WEBSITE_APPLICATION,
                        "заявка рабочего места",
                        SignatureType.SIMPLE_ES_SMS,
                        Map.of(
                                "phone", "+79160000045",
                                "otpVerifiedAt", Instant.now().toString(),
                                "otpHash", "hash",
                                "ip", "10.0.0.1",
                                "userAgent", "Mozilla")));
    }

    private MockHttpSession loginAs(String roleCode) throws Exception {
        AppUser user = accounts.create(roleCode);
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }
}
