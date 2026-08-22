package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.example.inconsensu.catalog.application.ConsentTypeService;
import ru.example.inconsensu.catalog.domain.ConsentType;
import ru.example.inconsensu.common.config.InConsensuProperties;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.ThirdPartyRole;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.notification.application.NotificationRuleService;
import ru.example.inconsensu.notification.application.NotificationService;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationRule;
import ru.example.inconsensu.notification.domain.NotificationStatus;
import ru.example.inconsensu.notification.domain.NotificationTrigger;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/**
 * Приёмка UI-13 на слое рабочего места: правила уведомлений, журнал отправок и повтор письма.
 *
 * <p>Проверяется ответ сервера одностраничному приложению, а не разметка. Форму правки приложение
 * собирает из ответа сервера, поэтому всё, чего в этом ответе нет, при следующем сохранении пропадёт:
 * отбор по типу согласия и третьему лицу, каналы и роли получателей. Таблица правил отдаёт готовые
 * подписи и для правки не годится — за это отвечает отдельный адрес правила.
 *
 * <p>Журнал точно так же считает и фильтрует сервер: отбор в памяти врал бы счётчиком страниц, а
 * граница «по дату» без включения самого дня прятала бы сегодняшние письма.
 *
 * <p>Прежние проверки ходили по страницам Thymeleaf ({@code UiNotificationIT}); правила от смены
 * интерфейса не изменились и переносятся сюда.
 */
@AutoConfigureMockMvc
class WorkplaceNotificationApiIT extends AbstractIntegrationTest {

    private static final String NOTIFICATIONS = "/ui/api/notifications";

    private static final String RULES = NOTIFICATIONS + "/rules";

    private static final String BODY = "Срок согласия заканчивается через 30 дней.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private NotificationRuleService rules;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private ConsentTypeService types;

    @Autowired
    private ThirdPartyService thirdParties;

    @Autowired
    private InConsensuProperties properties;

    /**
     * FR-9.2: правило умеет отбирать по типу согласия и по третьему лицу, и этот отбор возвращается.
     *
     * <p>Ключевая проверка раздела: если адрес правила не отдаёт отбор, каналы и роли, то правка,
     * собранная приложением из таблицы, молча обнулит всё, о чём таблица не рассказывает.
     */
    @Test
    void rule_returns_the_filters_channels_and_roles_it_was_saved_with() throws Exception {
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());
        ConsentType type = types.activeTypes().getFirst();
        ThirdParty party = createParty();
        String email = recipient("rule");
        String name = "Правило отбора " + suffix();

        String list = mockMvc.perform(post(RULES)
                        .session(dpo)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ruleBody(null, name, email, type.getId(), party.getId())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode row = ruleRowOf(list, name);
        // UI-0.4: в таблице отбор, каналы и роли названы по-русски, кодов справочника в ней нет.
        assertThat(row.path("filtersRu").asText())
                .contains("тип: " + type.getNameRu())
                .contains("третье лицо: " + party.getName());
        assertThat(row.path("recipients").asText())
                .contains(RoleCode.DPO.nameRu())
                .contains(email)
                .doesNotContain(RoleCode.DPO.name());
        assertThat(textsOf(row.path("channelsRu"))).containsExactly("письмо");
        assertThat(row.path("thresholds").asText()).isEqualTo("30, 7");

        UUID ruleId = UUID.fromString(row.path("id").asText());
        JsonNode details = ruleDetails(dpo, ruleId);
        assertThat(details.path("name").asText()).isEqualTo(name);
        assertThat(details.path("triggerType").asText()).isEqualTo(NotificationTrigger.EXPIRING.name());
        assertThat(details.path("daysBefore").asText()).isEqualTo("30, 7");
        assertThat(details.path("consentTypeId").asText())
                .isEqualTo(type.getId().toString());
        assertThat(details.path("thirdPartyId").asText())
                .isEqualTo(party.getId().toString());
        assertThat(textsOf(details.path("channels"))).containsExactly(NotificationChannel.EMAIL.name());
        assertThat(textsOf(details.path("recipientRoles"))).containsExactly(RoleCode.DPO.name());
        assertThat(textsOf(details.path("recipientEmails"))).containsExactly(email);
        assertThat(details.path("active").asBoolean()).isTrue();

        NotificationRule stored = rules.get(ruleId);
        assertThat(stored.getConsentTypeId())
                .as("отбор правила должен сохраняться, а не подменяться на null")
                .isEqualTo(type.getId());
        assertThat(stored.getThirdPartyId()).isEqualTo(party.getId());
    }

    /**
     * FR-9.2: повторное сохранение того же правила правит его и ничего не теряет по дороге.
     *
     * <p>Сохранение без каналов и получателей отклоняется, а не опустошает правило: письмо без канала
     * и без адресата никуда не уйдёт, и такое правило выглядело бы рабочим, оставаясь мёртвым.
     */
    @Test
    void resaving_the_rule_keeps_its_channels_and_recipients() throws Exception {
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());
        ConsentType type = types.activeTypes().getFirst();
        ThirdParty party = createParty();
        String email = recipient("resave");
        String name = "Правило правки " + suffix();
        UUID ruleId = saveRule(dpo, name, ruleBody(null, name, email, type.getId(), party.getId()));

        Map<String, Object> withoutRecipients = new LinkedHashMap<>();
        withoutRecipients.put("ruleId", ruleId);
        withoutRecipients.put("name", name);
        withoutRecipients.put("triggerType", NotificationTrigger.EXPIRING.name());
        withoutRecipients.put("daysBefore", "30, 7");
        mockMvc.perform(post(RULES)
                        .session(dpo)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(withoutRecipients)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        JsonNode kept = ruleDetails(dpo, ruleId);
        assertThat(textsOf(kept.path("channels")))
                .as("отклонённое сохранение не должно обнулять каналы")
                .containsExactly(NotificationChannel.EMAIL.name());
        assertThat(textsOf(kept.path("recipientRoles"))).containsExactly(RoleCode.DPO.name());

        // Второе сохранение с тем же номером правит правило, а не заводит второе с тем же смыслом.
        String renamed = name + " (уточнённое)";
        String list = mockMvc.perform(post(RULES)
                        .session(dpo)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ruleBody(ruleId, renamed, email, type.getId(), party.getId())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(namesOf(list)).contains(renamed).doesNotContain(name);
        assertThat(ruleRowOf(list, renamed).path("id").asText()).isEqualTo(ruleId.toString());

        JsonNode after = ruleDetails(dpo, ruleId);
        assertThat(textsOf(after.path("channels"))).containsExactly(NotificationChannel.EMAIL.name());
        assertThat(textsOf(after.path("recipientRoles"))).containsExactly(RoleCode.DPO.name());
        assertThat(textsOf(after.path("recipientEmails"))).containsExactly(email);
        assertThat(after.path("consentTypeId").asText()).isEqualTo(type.getId().toString());
        assertThat(after.path("thirdPartyId").asText()).isEqualTo(party.getId().toString());
    }

    /** UI-13: журнал отбирается по статусу, по правилу и по периоду, и «по дату» включает этот день. */
    @Test
    void journal_narrows_by_status_by_rule_and_by_the_period_including_the_closing_day() throws Exception {
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());
        String subjectLine = "In consensu: проверка журнала " + suffix();
        UUID ruleId = enqueue(recipient("journal"), subjectLine);
        UUID otherRuleId = createRule("Правило без писем " + suffix(), null, null, recipient("idle"))
                .getId();
        LocalDate today = LocalDate.now(properties.timezone());

        JsonNode mine = journal(dpo, "?ruleId=" + ruleId);
        assertThat(subjectsOf(mine)).containsExactly(subjectLine);
        // Счётчик считает по отбору, а не по всей таблице: иначе номера страниц журнала врут.
        assertThat(mine.path("total").asLong()).isEqualTo(1);
        assertThat(rowOf(mine, subjectLine).path("statusRu").asText()).isEqualTo(NotificationStatus.PENDING.nameRu());

        // Письмо ещё в очереди: отбор «отправлено» его не показывает, отбор по чужому правилу — тоже.
        assertThat(subjectsOf(journal(dpo, "?ruleId=" + ruleId + "&status=SENT")))
                .isEmpty();
        assertThat(subjectsOf(journal(dpo, "?ruleId=" + ruleId + "&status=PENDING")))
                .containsExactly(subjectLine);
        assertThat(subjectsOf(journal(dpo, "?ruleId=" + otherRuleId))).isEmpty();

        // Верхняя граница включает названный день целиком, иначе «по сегодня» прятало бы сегодняшние письма.
        assertThat(subjectsOf(journal(dpo, "?ruleId=" + ruleId + "&to=" + today)))
                .containsExactly(subjectLine);
        assertThat(subjectsOf(journal(dpo, "?ruleId=" + ruleId + "&to=" + today.minusDays(1))))
                .isEmpty();
        assertThat(subjectsOf(journal(dpo, "?ruleId=" + ruleId + "&from=" + today)))
                .containsExactly(subjectLine);
        assertThat(subjectsOf(journal(dpo, "?ruleId=" + ruleId + "&from=" + today.plusDays(1))))
                .isEmpty();
    }

    /** UI-13: письмо открывается целиком — сотрудник должен видеть, что именно ушло получателю. */
    @Test
    void message_is_opened_with_the_recipient_the_subject_and_the_full_text() throws Exception {
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());
        String recipient = recipient("message");
        String subjectLine = "In consensu: текст письма " + suffix();
        UUID ruleId = enqueue(recipient, subjectLine);
        UUID messageId = messageIdOf(journal(dpo, "?ruleId=" + ruleId), subjectLine);

        mockMvc.perform(get(NOTIFICATIONS + "/" + messageId).session(dpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient").value(recipient))
                .andExpect(jsonPath("$.subjectLine").value(subjectLine))
                .andExpect(jsonPath("$.body").value(BODY))
                .andExpect(jsonPath("$.channelRu").value(NotificationChannel.EMAIL.nameRu()))
                .andExpect(jsonPath("$.statusRu").value(NotificationStatus.PENDING.nameRu()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                // Письмо ещё не ушло: время отправки пустое, а не выдуманное.
                .andExpect(jsonPath("$.sentAt").value(""));
    }

    /** UI-13: повтор предлагается только для неудавшихся писем и возвращает письмо в очередь. */
    @Test
    void retry_is_offered_only_for_a_failed_message_and_returns_it_to_the_queue() throws Exception {
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());
        String subjectLine = "In consensu: повтор отправки " + suffix();
        UUID ruleId = enqueue(recipient("retry"), subjectLine);
        UUID messageId = messageIdOf(journal(dpo, "?ruleId=" + ruleId), subjectLine);

        // Письмо в очереди повторять нечего: оно ещё не отправлялось.
        JsonNode queued = rowOf(journal(dpo, "?ruleId=" + ruleId), subjectLine);
        assertThat(queued.path("canRetry").asBoolean()).isFalse();

        notifications.markFailed(List.of(messageId), "Почтовый сервер не ответил");
        JsonNode failed = rowOf(journal(dpo, "?ruleId=" + ruleId), subjectLine);
        assertThat(failed.path("canRetry").asBoolean()).isTrue();
        assertThat(failed.path("statusRu").asText()).isEqualTo(NotificationStatus.FAILED.nameRu());
        assertThat(failed.path("error").asText()).isEqualTo("Почтовый сервер не ответил");

        mockMvc.perform(post(NOTIFICATIONS + "/" + messageId + "/retry")
                        .session(dpo)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(containsString("очередь отправки")));

        // Повтор не отправляет письмо вторым путём, а возвращает его в общую очередь (UI-13).
        assertThat(notifications.get(messageId).getStatus()).isEqualTo(NotificationStatus.PENDING);
        JsonNode requeued = rowOf(journal(dpo, "?ruleId=" + ruleId), subjectLine);
        assertThat(requeued.path("canRetry").asBoolean()).isFalse();
        assertThat(requeued.path("error").isNull())
                .as("после возврата в очередь прошлая ошибка не показывается")
                .isTrue();
    }

    /** Приложение E: уведомлениями занимаются ответственный за ПДн и администратор, больше никто. */
    @Test
    void notifications_are_closed_for_every_role_except_the_dpo_and_the_administrator() throws Exception {
        for (RoleCode role : RoleCode.values()) {
            boolean allowed = role == RoleCode.DPO || role == RoleCode.ADMIN;
            MockHttpSession session = loginAs(role.name());

            MockHttpServletResponse rows = mockMvc.perform(
                            get(NOTIFICATIONS + "/journal").session(session))
                    .andReturn()
                    .getResponse();
            assertThat(rows.getStatus())
                    .as("журнал уведомлений для роли %s", role)
                    .isEqualTo(allowed ? 200 : 403);
            if (allowed) {
                continue;
            }
            assertDenied(rows, "журнал уведомлений для роли " + role);

            // Спрятать раздел в меню мало: сохранение правила отклоняет сервер, и правил не прибавляется.
            String name = "Правило без прав " + suffix();
            MockHttpServletResponse saved = mockMvc.perform(post(RULES)
                            .session(session)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(ruleBody(null, name, recipient("denied"), null, null)))
                    .andReturn()
                    .getResponse();
            assertThat(saved.getStatus())
                    .as("сохранение правила ролью %s", role)
                    .isEqualTo(403);
            assertDenied(saved, "сохранение правила ролью " + role);
            assertThat(rules.list().stream().map(NotificationRule::getName))
                    .as("отказ роли %s не должен заводить правило", role)
                    .doesNotContain(name);
        }
    }

    /** Отказ приходит телом ProblemDetail: приложение ждёт JSON, а не кусок вёрстки (UI-0.9). */
    private static void assertDenied(MockHttpServletResponse response, String where) throws Exception {
        String contentType = response.getContentType() == null ? "" : response.getContentType();
        assertThat(contentType).as("тип содержимого отказа: %s", where).startsWith("application/problem+json");

        JsonNode problem = MAPPER.readTree(response.getContentAsString());
        assertThat(problem.path("type").asText()).as("код ошибки: %s", where).startsWith("urn:inconsensu:error:");
        assertThat(problem.path("status").asInt())
                .as("код состояния: %s", where)
                .isEqualTo(403);
        // Причина не уточняется: рассказывать, чего не хватает, значит рассказывать о правах чужой роли.
        assertThat(problem.path("title").asText())
                .as("заголовок отказа: %s", where)
                .isNotBlank();
    }

    /** Форма сохранения правила целиком: приложение присылает все поля, а не разницу с прошлым разом. */
    private String ruleBody(UUID ruleId, String name, String email, UUID consentTypeId, UUID thirdPartyId)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleId", ruleId);
        body.put("name", name);
        body.put("triggerType", NotificationTrigger.EXPIRING.name());
        body.put("daysBefore", "30, 7");
        body.put("recipientEmails", List.of(email));
        body.put("recipientRoles", List.of(RoleCode.DPO.name()));
        body.put("channels", List.of(NotificationChannel.EMAIL.name()));
        body.put("consentTypeId", consentTypeId);
        body.put("thirdPartyId", thirdPartyId);
        return MAPPER.writeValueAsString(body);
    }

    /** Сохранение возвращает обновлённый список: номер нового правила приложение узнаёт только оттуда. */
    private UUID saveRule(MockHttpSession session, String name, String body) throws Exception {
        String list = mockMvc.perform(post(RULES)
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(ruleRowOf(list, name).path("id").asText());
    }

    private JsonNode ruleDetails(MockHttpSession session, UUID ruleId) throws Exception {
        String json = mockMvc.perform(get(RULES + "/" + ruleId).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return MAPPER.readTree(json);
    }

    private JsonNode journal(MockHttpSession session, String query) throws Exception {
        String json = mockMvc.perform(get(NOTIFICATIONS + "/journal" + query).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return MAPPER.readTree(json);
    }

    /** Правило и письмо в очереди: журнал проверяется на своих записях, а не на чужих. */
    private UUID enqueue(String recipient, String subjectLine) {
        NotificationRule rule = createRule("Журнал " + suffix(), null, null, recipient);
        notifications.enqueue(
                rule.getId(),
                null,
                null,
                "ui-api-journal-" + UUID.randomUUID(),
                NotificationChannel.EMAIL,
                recipient,
                subjectLine,
                BODY,
                Map.of());
        return rule.getId();
    }

    /** Правило журнала заводится сервисом: проверяется отбор записей, а не путь их появления. */
    private NotificationRule createRule(String name, UUID consentTypeId, UUID thirdPartyId, String recipient) {
        return RunAs.roles(
                "test-admin",
                List.of(RoleCode.ADMIN.name()),
                () -> rules.create(new NotificationRuleService.RuleForm(
                        name,
                        NotificationTrigger.EXPIRING,
                        List.of(30),
                        consentTypeId,
                        thirdPartyId,
                        Set.of(recipient),
                        Set.of(RoleCode.DPO.name()),
                        Set.of(NotificationChannel.EMAIL),
                        true)));
    }

    /** Третье лицо для отбора правила: без него фильтр по партнёру нечем проверить (FR-9.2). */
    private ThirdParty createParty() {
        String inn = String.valueOf(7_700_000_000L + Math.abs(UUID.randomUUID().hashCode() % 1_000_000));
        return RunAs.roles(
                "test-admin",
                List.of(RoleCode.ADMIN.name()),
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
                                LocalDate.now().plusYears(1),
                                Set.of("FIO", "PHONE"),
                                "dpo@momento.example")));
    }

    /** Разбор ответа: фильтры JsonPath по значению поля здесь не работают, а порядок строк не задан. */
    private static JsonNode ruleRowOf(String json, String name) throws Exception {
        for (JsonNode row : MAPPER.readTree(json)) {
            if (name.equals(row.path("name").asText())) {
                return row;
            }
        }
        throw new AssertionError("В списке правил нет правила «" + name + "»");
    }

    private static List<String> namesOf(String json) throws Exception {
        List<String> names = new ArrayList<>();
        MAPPER.readTree(json).forEach(row -> names.add(row.path("name").asText()));
        return names;
    }

    private static JsonNode rowOf(JsonNode journal, String subjectLine) {
        for (JsonNode row : journal.path("rows")) {
            if (subjectLine.equals(row.path("subjectLine").asText())) {
                return row;
            }
        }
        throw new AssertionError("В журнале нет письма «" + subjectLine + "»");
    }

    private static UUID messageIdOf(JsonNode journal, String subjectLine) {
        return UUID.fromString(rowOf(journal, subjectLine).path("id").asText());
    }

    private static List<String> subjectsOf(JsonNode journal) {
        List<String> subjects = new ArrayList<>();
        journal.path("rows").forEach(row -> subjects.add(row.path("subjectLine").asText()));
        return subjects;
    }

    private static List<String> textsOf(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.asText()));
        return values;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Вымышленный служебный адрес: настоящих контактов в тестовых данных быть не должно. */
    private static String recipient(String tag) {
        return tag + "-" + suffix() + "@example.ru";
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
