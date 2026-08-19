package ru.example.inconsensu.integration.application;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.catalog.application.FormWorkflowService;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.catalog.domain.ConsentFormItem;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RevocationSource;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.common.domain.ThirdPartyRole;
import ru.example.inconsensu.iam.application.OperatorSettingsService;
import ru.example.inconsensu.iam.application.UserService;
import ru.example.inconsensu.registry.application.ConsentDemoSupport;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.registry.domain.Consent;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/**
 * Демонстрационные данные профиля {@code demo} (§11, §13).
 *
 * <p>Данные создаются через те же application-сервисы, что и боевые: форма проходит согласование и публикацию,
 * контрольная сумма считается по-настоящему, каждое действие попадает в журнал аудита. Загрузка SQL-дампом
 * дала бы картинку, но не доказательство, что механизмы работают.
 *
 * <p>Все персональные данные вымышленные (§14.6). Загрузчик идемпотентен: повторный запуск ничего не дублирует.
 *
 * <p>Даты согласий — момент установки, а не задним числом: форма публикуется здесь же, и согласие, датированное
 * прошлым годом, справедливо отвергается правилом FR-2.3 «форма должна действовать на дату согласия».
 */
@Component
@Profile("demo")
public class DemoDataLoader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoDataLoader.class);

    /** Пароль демо-пользователей. Профиль demo не предназначен для эксплуатации, см. README. */
    public static final String DEMO_PASSWORD = "demo-password-2026";

    public static final String TRAVIN_EXTERNAL_ID = "CRM-1002345";
    private static final String CHKALOV_EXTERNAL_ID = "CRM-1002346";
    private static final String BONDARENKO_EXTERNAL_ID = "CRM-1002347";
    private static final String MOMENTO_INN = "7714123456";
    private static final int TRANSFER_DAYS_LEFT = 15;

    private final UserService userService;
    private final OperatorSettingsService settings;
    private final ThirdPartyService thirdParties;
    private final ConsentFormService forms;
    private final FormWorkflowService workflow;
    private final SubjectService subjects;
    private final ConsentRegistrationService registration;
    private final ConsentDemoSupport demoSupport;
    private final java.time.Clock clock;

    public DemoDataLoader(
            UserService userService,
            OperatorSettingsService settings,
            ThirdPartyService thirdParties,
            ConsentFormService forms,
            FormWorkflowService workflow,
            SubjectService subjects,
            ConsentRegistrationService registration,
            ConsentDemoSupport demoSupport,
            java.time.Clock clock) {
        this.userService = userService;
        this.settings = settings;
        this.thirdParties = thirdParties;
        this.forms = forms;
        this.workflow = workflow;
        this.subjects = subjects;
        this.registration = registration;
        this.demoSupport = demoSupport;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (subjects.findByExternalId(TRAVIN_EXTERNAL_ID).isPresent()) {
            LOG.info("Демо-данные уже загружены");
            return;
        }
        LOG.info("Загружаю демонстрационные данные профиля demo (§11)");

        asSystem(List.of(RoleCode.ADMIN.name(), RoleCode.DPO.name()), this::createUsersAndSettings);
        ThirdParty momento = asSystem(List.of(RoleCode.DPO.name()), this::createThirdParty);
        ConsentForm form = createPublishedForm(momento);
        asSystem(List.of(RoleCode.INTEGRATION.name()), () -> {
            createTravin(form, momento);
            createOtherSubjects(form);
            return null;
        });

        LOG.info(
                "Демо-данные загружены: субъекты {}, {}, {}",
                TRAVIN_EXTERNAL_ID,
                CHKALOV_EXTERNAL_ID,
                BONDARENKO_EXTERNAL_ID);
    }

    private Void createUsersAndSettings() {
        settings.update(Map.of(
                "operator.name", "ООО «Демонстрационный оператор»",
                "operator.address", "123001, г. Москва, ул. Примерная, д. 1",
                "operator.inn", "7701234567",
                "dpo.name", "Иванова Анна Андреевна",
                "dpo.email", "dpo@example.ru"));

        // Вход под каждой ролью нужен для проверки матрицы прав §16.2 в интерфейсе этапа 7.
        for (RoleCode role : RoleCode.values()) {
            String login = role.name().toLowerCase(java.util.Locale.ROOT);
            if (!userService.existsByLogin(login)) {
                userService.create(
                        login, DEMO_PASSWORD, "Демо · " + role.nameRu(), login + "@example.ru", Set.of(role.name()));
            }
        }
        return null;
    }

    private ThirdParty createThirdParty() {
        return thirdParties.create(
                MOMENTO_INN,
                new ThirdPartyService.ThirdPartyForm(
                        "Общество с ограниченной ответственностью «Моменто»",
                        "ООО «Моменто»",
                        "1027700123456",
                        "115035, г. Москва, ул. Курьерская, д. 7",
                        ThirdPartyRole.PROCESSOR,
                        "ДС-2025/117",
                        java.time.LocalDate.now(clock).minusYears(1),
                        java.time.LocalDate.now(clock).plusYears(1),
                        Set.of("FIO", "PHONE", "POSTAL_ADDRESS", "EMAIL"),
                        "dpo@momento.example"));
    }

    private ConsentForm createPublishedForm(ThirdParty momento) {
        List<ConsentFormService.ItemForm> items = List.of(
                new ConsentFormService.ItemForm(
                        "PDN_PROCESSING",
                        "Согласие на обработку персональных данных",
                        List.of("рассмотрение заявки и заключение договора"),
                        List.of("FIO", "PHONE", "EMAIL"),
                        null,
                        null,
                        true),
                new ConsentFormService.ItemForm(
                        "ADVERTISING_PHONE",
                        "Согласие на получение рекламы по телефону",
                        List.of("информирование о продуктах и акциях"),
                        List.of("PHONE"),
                        null,
                        null,
                        false),
                new ConsentFormService.ItemForm(
                        "ADVERTISING_EMAIL",
                        "Согласие на получение рекламы по электронной почте",
                        List.of("информирование о продуктах и акциях"),
                        List.of("EMAIL"),
                        null,
                        null,
                        false),
                new ConsentFormService.ItemForm(
                        "PDN_TRANSFER",
                        "Согласие на передачу персональных данных для доставки корреспонденции",
                        List.of("доставка корреспонденции"),
                        List.of("FIO", "PHONE", "POSTAL_ADDRESS", "EMAIL"),
                        momento.getId(),
                        "P1Y",
                        false));

        ConsentForm draft = asSystem(
                List.of(RoleCode.LAWYER.name()),
                () -> forms.createDraft(
                        "CONTRACT_MAIN",
                        new ConsentFormService.FormDraft(
                                "Согласие на обработку персональных данных к договору",
                                """
                        Я, {{subject.fio}}, телефон {{subject.phone}}, электронная почта {{subject.email}},
                        даю согласие {{operator.name}} (адрес: {{operator.address}}) на обработку моих
                        персональных данных на условиях, указанных ниже.
                        """,
                                "сбор, запись, систематизация, накопление, хранение, уточнение, извлечение, "
                                        + "использование, передача (предоставление, доступ), блокирование, удаление, "
                                        + "уничтожение; обработка автоматизированная и неавтоматизированная",
                                "согласие действует до отзыва; отзыв — в личном кабинете, мобильном приложении "
                                        + "или письменным заявлением по адресу оператора",
                                Set.of(ConsentSource.CONTRACT, ConsentSource.WEBSITE_APPLICATION),
                                items)));

        asSystem(List.of(RoleCode.LAWYER.name()), () -> workflow.submit(draft.getId()));
        asSystem(List.of(RoleCode.LAWYER.name()), () -> workflow.approve(draft.getId(), "Формулировки согласованы"));
        asSystem(List.of(RoleCode.DPO.name()), () -> workflow.approve(draft.getId(), "Реквизиты проверены"));
        return asSystem(List.of(RoleCode.DPO.name()), () -> workflow.publish(draft.getId()));
    }

    /** Картина Травина из §11 и Приложения A: два действующих, одно отозванное, одно истекающее согласие. */
    private void createTravin(ConsentForm form, ThirdParty momento) {
        var subject = new SubjectService.SubjectForm(
                TRAVIN_EXTERNAL_ID,
                "Травин",
                "Иван",
                "Сергеевич",
                java.time.LocalDate.of(1985, 4, 12),
                List.of(
                        new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-41", true),
                        new SubjectService.ContactForm(ContactType.EMAIL, "travin@example.ru", true),
                        new SubjectService.ContactForm(
                                ContactType.POSTAL_ADDRESS, "129301, г. Москва, ул. Кленовая, д. 3, кв. 12", true)));

        var created = registration.register(
                "demo-travin-" + UUID.randomUUID(),
                new ConsentRegistrationService.RegistrationRequest(
                        null,
                        subject,
                        form.getId(),
                        form.getItems().stream()
                                .map(item -> new ConsentRegistrationService.ItemDecision(item.getId(), true))
                                .toList(),
                        clock.instant(),
                        ConsentSource.CONTRACT,
                        "Д-2025/4471",
                        SignatureType.SIMPLE_ES_SMS,
                        Map.of(
                                "phone", "+79160000041",
                                "otpVerifiedAt", clock.instant().toString(),
                                "otpHash", "demo-hash",
                                "ip", "10.0.0.1",
                                "userAgent", "Mozilla/5.0")));

        for (Consent consent : created.created()) {
            ConsentFormItem item = form.getItems().stream()
                    .filter(candidate -> candidate.getId().equals(consent.getFormItemId()))
                    .findFirst()
                    .orElseThrow();
            String typeCode = item.getConsentType().getCode();

            if ("ADVERTISING_EMAIL".equals(typeCode)) {
                // Отзыв через сервис появится на этапе 5; здесь достаточно доменной операции над согласием.
                demoSupport.revoke(
                        consent.getId(),
                        clock.instant(),
                        "Клиент отказался от рекламных рассылок",
                        RevocationSource.PERSONAL_ACCOUNT);
            }
            if ("PDN_TRANSFER".equals(typeCode)) {
                // §11: передача ООО «Моменто» заканчивается через 15 дней — иначе демо не покажет статус EXPIRING.
                demoSupport.setValidUntil(consent.getId(), clock.instant().plus(TRANSFER_DAYS_LEFT, ChronoUnit.DAYS));
            }
        }
    }

    private void createOtherSubjects(ConsentForm form) {
        registerBaseConsent(
                form,
                new SubjectService.SubjectForm(
                        CHKALOV_EXTERNAL_ID,
                        "Чкалов",
                        "Пётр",
                        "Алексеевич",
                        java.time.LocalDate.of(1979, 11, 3),
                        List.of(new SubjectService.ContactForm(ContactType.PHONE, "+7 916 000-00-42", true))));

        registerBaseConsent(
                form,
                new SubjectService.SubjectForm(
                        BONDARENKO_EXTERNAL_ID,
                        "Бондаренко",
                        "Светлана",
                        "Викторовна",
                        java.time.LocalDate.of(1992, 6, 21),
                        List.of(new SubjectService.ContactForm(ContactType.EMAIL, "bondarenko@example.ru", true))));
    }

    private void registerBaseConsent(ConsentForm form, SubjectService.SubjectForm subject) {
        UUID baseItemId = form.getItems().stream()
                .filter(item -> "PDN_PROCESSING".equals(item.getConsentType().getCode()))
                .findFirst()
                .orElseThrow()
                .getId();

        registration.register(
                "demo-" + subject.externalId() + "-" + UUID.randomUUID(),
                new ConsentRegistrationService.RegistrationRequest(
                        null,
                        subject,
                        form.getId(),
                        List.of(new ConsentRegistrationService.ItemDecision(baseItemId, true)),
                        clock.instant(),
                        ConsentSource.PERSONAL_ACCOUNT_REGISTRATION,
                        "ЛК",
                        SignatureType.SIMPLE_ES_LK,
                        Map.of(
                                "accountId", subject.externalId(),
                                "authMethod", "password",
                                "actionAt", clock.instant().toString(),
                                "ip", "10.0.0.2",
                                "userAgent", "Mozilla/5.0")));
    }

    /** Демо-данные создаются от имени системы, но с ролями, которых требуют проверки workflow (FR-2.1). */
    private <T> T asSystem(List<String> roles, java.util.function.Supplier<T> action) {
        var authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("system", "n/a", authorities));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
