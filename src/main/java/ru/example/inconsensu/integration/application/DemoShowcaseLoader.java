package ru.example.inconsensu.integration.application;

import java.time.LocalDate;
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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.example.inconsensu.audit.application.AuditVerificationService;
import ru.example.inconsensu.catalog.application.ConsentFormService;
import ru.example.inconsensu.catalog.application.FormWorkflowService;
import ru.example.inconsensu.catalog.domain.ConsentForm;
import ru.example.inconsensu.common.domain.ConsentSource;
import ru.example.inconsensu.common.domain.ContactType;
import ru.example.inconsensu.common.domain.RevocationSource;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.SignatureType;
import ru.example.inconsensu.common.domain.ThirdPartyRole;
import ru.example.inconsensu.notification.application.NotificationDispatcher;
import ru.example.inconsensu.notification.application.NotificationJob;
import ru.example.inconsensu.notification.application.NotificationRuleService;
import ru.example.inconsensu.notification.application.OutboxProcessor;
import ru.example.inconsensu.notification.application.WebhookSubscriptionService;
import ru.example.inconsensu.notification.domain.NotificationChannel;
import ru.example.inconsensu.notification.domain.NotificationTrigger;
import ru.example.inconsensu.registry.application.ConsentDemoSupport;
import ru.example.inconsensu.registry.application.ConsentRegistrationService;
import ru.example.inconsensu.registry.application.RevocationService;
import ru.example.inconsensu.registry.application.SubjectService;
import ru.example.inconsensu.thirdparty.application.PartnerExportService;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/**
 * Витрина профиля {@code demo}: данные во всех разделах интерфейса.
 *
 * <p>{@link DemoDataLoader} создаёт ровно то, что нужно сквозному сценарию §11, и после него половина
 * экранов остаётся пустой: список форм из одной строки, ни одной задачи импорта, пустые журналы уведомлений
 * и доставок. Смотреть такой интерфейс и оценивать его невозможно. Здесь добавляется остальное — партнёры с
 * разными сроками договора, формы во всех статусах, клиенты со всеми статусами согласий, прогон импорта,
 * правила и журнал уведомлений, подписки и попытки доставки.
 *
 * <p>Данные создаются теми же application-сервисами, что и боевые: статусы, контрольные суммы, журнал
 * аудита и очередь событий получаются настоящими, а не нарисованными. Все ФИО, адреса, ИНН и телефоны
 * вымышленные (§14.6), домен `example.ru` не резолвится.
 *
 * <p>Загрузчик идемпотентен: признаком служит партнёр «Логистик-Экспресс».
 */
@Component
@Profile("demo")
@Order(100)
public class DemoShowcaseLoader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoShowcaseLoader.class);

    private static final String MARKER_INN = "7726004455";

    private final ThirdPartyService thirdParties;
    private final PartnerExportService exports;
    private final ConsentFormService forms;
    private final FormWorkflowService workflow;
    private final SubjectService subjects;
    private final ConsentRegistrationService registration;
    private final RevocationService revocation;
    private final ConsentDemoSupport demoSupport;
    private final ConsentImportService imports;
    private final NotificationRuleService rules;
    private final NotificationJob notificationJob;
    private final NotificationDispatcher dispatcher;
    private final WebhookSubscriptionService subscriptions;
    private final OutboxProcessor outbox;
    private final AuditVerificationService verifications;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final java.time.Clock clock;

    /** Форма, по которой регистрируются демонстрационные согласия: снимается один раз при загрузке. */
    private UUID publishedFormId;

    public DemoShowcaseLoader(
            ThirdPartyService thirdParties,
            PartnerExportService exports,
            ConsentFormService forms,
            FormWorkflowService workflow,
            SubjectService subjects,
            ConsentRegistrationService registration,
            RevocationService revocation,
            ConsentDemoSupport demoSupport,
            ConsentImportService imports,
            NotificationRuleService rules,
            NotificationJob notificationJob,
            NotificationDispatcher dispatcher,
            WebhookSubscriptionService subscriptions,
            OutboxProcessor outbox,
            AuditVerificationService verifications,
            org.springframework.transaction.support.TransactionTemplate transactions,
            java.time.Clock clock) {
        this.thirdParties = thirdParties;
        this.exports = exports;
        this.forms = forms;
        this.workflow = workflow;
        this.subjects = subjects;
        this.registration = registration;
        this.revocation = revocation;
        this.demoSupport = demoSupport;
        this.imports = imports;
        this.rules = rules;
        this.notificationJob = notificationJob;
        this.dispatcher = dispatcher;
        this.subscriptions = subscriptions;
        this.outbox = outbox;
        this.verifications = verifications;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean alreadyLoaded = thirdParties.list(org.springframework.data.domain.Pageable.unpaged()).stream()
                .anyMatch(party -> MARKER_INN.equals(party.getInn()));
        if (alreadyLoaded) {
            LOG.info("Витрина демо-данных уже загружена");
            return;
        }
        LOG.info("Загружаю витрину демо-данных: партнёры, формы, клиенты, импорт, уведомления, подписки");

        List<ThirdParty> partners = as(List.of(RoleCode.DPO.name()), this::createPartners);
        ConsentForm published = forms.publishedVersionOf("CONTRACT_MAIN").orElse(null);
        if (published == null) {
            LOG.warn("Витрина демо-данных: опубликованной формы CONTRACT_MAIN нет, клиенты не создаются");
            return;
        }

        // Каждый кусок витрины ставится отдельно и не роняет запуск: демонстрационные данные — удобство,
        // а не условие работы системы. Отказ пишется предупреждением с причиной, остальное грузится дальше.
        step("выгрузка партнёру", List.of(RoleCode.DPO.name()), () -> createExport(partners.get(0)));
        step(
                "формы во всех статусах",
                List.of(RoleCode.LAWYER.name(), RoleCode.DPO.name()),
                this::createFormsInEveryStatus);
        publishedFormId = published.getId();
        step("клиенты", List.of(RoleCode.INTEGRATION.name()), () -> createClients(published));
        step("отзыв рекламы", List.of(RoleCode.MANAGER.name()), () -> revokeForSomeone(published));
        step("импорт", List.of(RoleCode.ADMIN.name()), this::runImports);
        step("правила уведомлений", List.of(RoleCode.ADMIN.name()), this::createNotificationRules);
        step("подписки на события", List.of(RoleCode.ADMIN.name()), this::createSubscriptions);
        step("прогон фоновых задач", List.of(RoleCode.ADMIN.name()), this::runBackgroundOnce);

        LOG.info("Витрина демо-данных загружена");
    }

    /** Партнёры с разными ролями и сроками договора: список третьих лиц показывает все бейджи UI-0.7. */
    private List<ThirdParty> createPartners() {
        LocalDate today = LocalDate.now(clock);
        ThirdParty logistics = thirdParties.create(
                MARKER_INN,
                new ThirdPartyService.ThirdPartyForm(
                        "Общество с ограниченной ответственностью «Логистик-Экспресс»",
                        "ООО «Логистик-Экспресс»",
                        "1027700455566",
                        "141400, Московская область, г. Химки, ул. Складская, д. 4",
                        ThirdPartyRole.RECIPIENT,
                        "ДП-2024/58",
                        today.minusYears(2),
                        // Договор заканчивается через 20 дней — на списке загорается оранжевый бейдж.
                        today.plusDays(20),
                        Set.of("FIO", "PHONE", "POSTAL_ADDRESS"),
                        "dpo@logistic.example.ru"));

        ThirdParty bureau = thirdParties.create(
                "7708009911",
                new ThirdPartyService.ThirdPartyForm(
                        "Акционерное общество «Кредитное бюро Ясность»",
                        "АО «Ясность»",
                        "1037700778899",
                        "125009, г. Москва, Тверская ул., д. 12, стр. 3",
                        ThirdPartyRole.ECOSYSTEM,
                        "ДС-2026/9",
                        today.minusMonths(3),
                        today.plusYears(2),
                        Set.of("FIO", "PASSPORT", "PHONE"),
                        "compliance@yasnost.example.ru"));

        ThirdParty archive = thirdParties.create(
                "5260112233",
                new ThirdPartyService.ThirdPartyForm(
                        "Общество с ограниченной ответственностью «Архив-Сервис»",
                        "ООО «Архив-Сервис»",
                        "1057700334455",
                        "603000, г. Нижний Новгород, ул. Бумажная, д. 21",
                        ThirdPartyRole.PROCESSOR,
                        "ДП-2022/3",
                        today.minusYears(4),
                        // Договор истёк: карточка и список обязаны сказать об этом красным.
                        today.minusDays(45),
                        Set.of("FIO", "POSTAL_ADDRESS"),
                        "info@archiv.example.ru"));

        ThirdParty promo = thirdParties.create(
                "7801445566",
                new ThirdPartyService.ThirdPartyForm(
                        "Общество с ограниченной ответственностью «Промо-Партнёр»",
                        "ООО «Промо-Партнёр»",
                        "1067700998877",
                        "190000, г. Санкт-Петербург, наб. Рекламная, д. 8",
                        ThirdPartyRole.RECIPIENT,
                        "ДП-2023/41",
                        today.minusYears(3),
                        today.plusMonths(6),
                        Set.of("EMAIL", "PHONE"),
                        "partner@promo.example.ru"));
        thirdParties.deactivate(promo.getId());

        return List.of(logistics, bureau, archive, promo);
    }

    /** Вкладка «Выгрузки» карточки партнёра: без хотя бы одной выгрузки она выглядит нерабочей. */
    private Void createExport(ThirdParty partner) {
        exports.create(partner.getId(), "csv");
        return null;
    }

    /** Формы во всех статусах: список UI-7 показывает черновик, согласование, публикацию и архив. */
    private Void createFormsInEveryStatus() {
        // Тип берётся из справочника Приложения B: выдуманный код форму не создаст.
        ConsentFormService.ItemForm research = new ConsentFormService.ItemForm(
                "ADVERTISING_MESSENGER",
                "Согласие на сообщения в мессенджерах",
                List.of("изучение мнения о продуктах", "приглашения на опросы"),
                List.of("FIO", "PHONE"),
                null,
                "P1Y",
                false);

        // Черновик: юрист ещё пишет текст.
        forms.createDraft(
                "RESEARCH_2026",
                new ConsentFormService.FormDraft(
                        "Согласие на сообщения в мессенджерах для опросов",
                        """
                Я, {{subject.fio}}, электронная почта {{subject.email}}, соглашаюсь участвовать в
                исследованиях {{operator.name}} (адрес: {{operator.address}}).
                """,
                        "сбор, запись, хранение, использование, уничтожение",
                        "согласие действует год; отзыв — в личном кабинете",
                        Set.of(ConsentSource.WEBSITE_APPLICATION),
                        List.of(research)));

        // На согласовании: у юриста и DPO появляется работа, счётчик в меню перестаёт быть нулём.
        ConsentForm review = forms.createDraft(
                "LOYALTY_2026",
                new ConsentFormService.FormDraft(
                        "Согласие участника программы лояльности",
                        """
                Я, {{subject.fio}}, телефон {{subject.phone}}, вступаю в программу лояльности
                {{operator.name}} (адрес: {{operator.address}}) и соглашаюсь на обработку данных о покупках.
                """,
                        "сбор, запись, систематизация, хранение, использование, уничтожение",
                        "согласие действует до отзыва; отзыв — в личном кабинете или в офисе",
                        Set.of(ConsentSource.OFFICE, ConsentSource.WEBSITE_APPLICATION),
                        List.of(new ConsentFormService.ItemForm(
                                "LOYALTY_PROGRAM",
                                "Согласие на обработку данных о покупках",
                                List.of("начисление бонусов", "персональные предложения"),
                                List.of("FIO", "PHONE"),
                                null,
                                null,
                                false))));
        workflow.submit(review.getId());
        return null;
    }

    /**
     * Клиенты со всеми статусами согласий: действует, заканчивается, истекло, отозвано, заменено.
     *
     * <p>Поиск, дашборд и отчёты каталога без этого показывают три строки и нули в счётчиках.
     */
    private Void createClients(ConsentForm form) {
        // Пункты формы читаются лениво: идентификаторы снимаются внутри транзакции, снаружи у сущности уже
        // нет сессии и обращение к списку пунктов падает.
        Items items = transactions.execute(status -> {
            ConsentForm managed = forms.get(form.getId());
            return new Items(
                    managed.getItems().stream()
                            .map(ru.example.inconsensu.catalog.domain.ConsentFormItem::getId)
                            .toList(),
                    itemId(managed, "PDN_PROCESSING"),
                    itemId(managed, "ADVERTISING_PHONE"),
                    itemId(managed, "ADVERTISING_EMAIL"));
        });
        UUID baseItem = items.base();
        UUID phoneItem = items.phone();
        UUID emailItem = items.email();

        record Person(String externalId, String lastName, String firstName, String middleName, String phone) {}
        List<Person> people = List.of(
                new Person("CRM-1002401", "Северцева", "Ольга", "Дмитриевна", "+7 916 000-01-01"),
                new Person("CRM-1002402", "Гурьев", "Никита", "Павлович", "+7 916 000-01-02"),
                new Person("CRM-1002403", "Лазарева", "Марина", "Игоревна", "+7 916 000-01-03"),
                new Person("CRM-1002404", "Понизовский", "Артём", "Львович", "+7 916 000-01-04"),
                new Person("CRM-1002405", "Бегичева", "Дарья", "Олеговна", "+7 916 000-01-05"),
                new Person("CRM-1002406", "Ковтун", "Игорь", "Степанович", "+7 916 000-01-06"),
                new Person("CRM-1002407", "Ямпольская", "Вера", "Борисовна", "+7 916 000-01-07"),
                new Person("CRM-1002408", "Дорошенко", "Кирилл", "Максимович", "+7 916 000-01-08"),
                new Person("CRM-1002409", "Митрофанова", "Алла", "Юрьевна", "+7 916 000-01-09"),
                new Person("CRM-1002410", "Шелест", "Роман", "Витальевич", "+7 916 000-01-10"));

        int index = 0;
        for (Person person : people) {
            var subject = new SubjectService.SubjectForm(
                    person.externalId(),
                    person.lastName(),
                    person.firstName(),
                    person.middleName(),
                    LocalDate.of(1980 + index, 1 + index % 12, 1 + index % 28),
                    List.of(
                            new SubjectService.ContactForm(ContactType.PHONE, person.phone(), true),
                            new SubjectService.ContactForm(
                                    ContactType.EMAIL,
                                    person.lastName()
                                                    .toLowerCase(java.util.Locale.ROOT)
                                                    .charAt(0)
                                            + person.externalId().substring(4) + "@example.ru",
                                    true)));

            List<UUID> accepted = index % 3 == 0 ? List.of(baseItem, phoneItem) : List.of(baseItem, emailItem);
            var created = register(items.all(), subject, accepted, person.externalId());

            // Разные сроки: у части клиентов согласие заканчивается — дашборд и уведомления оживают.
            int daysLeft =
                    switch (index % 5) {
                        case 0 -> 7;
                        case 1 -> 15;
                        case 2 -> 30;
                        default -> 0;
                    };
            if (daysLeft > 0) {
                created.created()
                        .forEach(consent -> demoSupport.setValidUntil(
                                consent.getId(), clock.instant().plus(daysLeft, ChronoUnit.DAYS)));
            }
            if (index % 5 == 3) {
                // Истёкшее согласие: статус считается по дате, отдельного действия не нужно.
                created.created()
                        .forEach(consent -> demoSupport.setValidUntil(
                                consent.getId(), clock.instant().minus(10, ChronoUnit.DAYS)));
            }
            if (index % 5 == 4) {
                // Повторная регистрация того же типа заменяет прежнее согласие (FR-4.3) — статус «заменено».
                register(items.all(), subject, List.of(baseItem), person.externalId() + "-again");
            }
            index++;
        }
        return null;
    }

    /** Отзыв рукой менеджера: карточка, журнал и уведомление об отзыве получают настоящие записи. */
    private Void revokeForSomeone(ConsentForm form) {
        subjects.findByExternalId("CRM-1002402").ifPresent(subject -> {
            var revoked = revocation.revokeAllAdvertising(
                    subject.getId(),
                    "Клиент попросил прекратить рекламу",
                    RevocationSource.CALL_CENTER,
                    "ОБР-2026/318",
                    Map.of());
            LOG.debug("Демо: отозвано рекламных согласий {}", revoked.size());
        });
        return null;
    }

    /** Импорт: пробный прогон с ошибками и боевой прогон — на экране UI-12 появляются задачи и отчёт. */
    private Void runImports() {
        String header = String.join(
                ",",
                "external_id",
                "last_name",
                "first_name",
                "middle_name",
                "phone",
                "email",
                "consent_type_code",
                "form_code",
                "form_version",
                "granted_at",
                "valid_until",
                "source",
                "source_ref",
                "third_party_inn",
                "pdn_categories",
                "document_ref",
                "note");

        String good = header + "\n"
                + row("CRM-2000101", "Ветрова", "Инна", "Сергеевна", "01", "PDN_PROCESSING", "перенос из базы клиентов")
                + row(
                        "CRM-2000102",
                        "Одинцов",
                        "Глеб",
                        "Русланович",
                        "02",
                        "PDN_PROCESSING",
                        "перенос из базы клиентов")
                + row(
                        "CRM-2000103",
                        "Раевская",
                        "Нина",
                        "Петровна",
                        "03",
                        "PDN_PROCESSING",
                        "перенос из базы клиентов");

        // Две строки заведомо негодные: отчёт покажет номер строки и причину, как требует UI-12.
        String withErrors = good
                + row("CRM-2000104", "Смирнов", "Олег", "", "04", "НЕТ_ТАКОГО_ТИПА", "перенос")
                + row("CRM-2000105", "Тихая", "Юлия", "", "05", "PDN_PROCESSING", "");

        imports.start("Проверка выгрузки CRM.csv", bytes(withErrors), "CLIENT_BASE_IMPORT", true);
        imports.start("Перенос базы клиентов.csv", bytes(good), "CLIENT_BASE_IMPORT", false);
        return null;
    }

    /** Строка файла импорта: поля идут в том же порядке, что и заголовок. */
    private String row(
            String externalId,
            String lastName,
            String firstName,
            String middleName,
            String suffix,
            String type,
            String note) {
        return String.join(
                        ",",
                        externalId,
                        lastName,
                        firstName,
                        middleName,
                        "+7 916 000-02-" + suffix,
                        translit(lastName) + "@example.ru",
                        type,
                        "CONTRACT_MAIN",
                        "1",
                        today(),
                        "",
                        "CLIENT_BASE_IMPORT",
                        "Б-2019/" + suffix,
                        "",
                        "FIO;PHONE",
                        "",
                        note)
                + "\n";
    }

    /** Адрес почты для демо-файла: домен example.ru не резолвится, письма никуда не уйдут (§14.6). */
    private static String translit(String lastName) {
        return "client-" + Integer.toHexString(lastName.hashCode() & 0xffff);
    }

    private static byte[] bytes(String csv) {
        return csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Правила уведомлений по всем поводам: вкладка «Правила» перестаёт быть пустой, задача находит работу. */
    private Void createNotificationRules() {
        rules.create(new NotificationRuleService.RuleForm(
                "Переподписание: 30, 15 и 7 дней",
                NotificationTrigger.EXPIRING,
                List.of(30, 15, 7),
                null,
                null,
                Set.of(),
                Set.of(RoleCode.DPO.name()),
                Set.of(NotificationChannel.EMAIL),
                true));
        rules.create(new NotificationRuleService.RuleForm(
                "Отзыв согласия — ответственному",
                NotificationTrigger.REVOKED,
                List.of(),
                null,
                null,
                Set.of("dpo@example.ru"),
                Set.of(RoleCode.DPO.name()),
                Set.of(NotificationChannel.EMAIL),
                true));
        rules.create(new NotificationRuleService.RuleForm(
                "Договор с третьим лицом заканчивается",
                NotificationTrigger.THIRD_PARTY_CONTRACT_EXPIRING,
                List.of(30, 10),
                null,
                null,
                Set.of(),
                Set.of(RoleCode.LAWYER.name(), RoleCode.DPO.name()),
                Set.of(NotificationChannel.EMAIL),
                true));
        rules.create(new NotificationRuleService.RuleForm(
                "Событие не доставлено — администратору",
                NotificationTrigger.DELIVERY_FAILED,
                List.of(),
                null,
                null,
                Set.of(),
                Set.of(RoleCode.ADMIN.name()),
                Set.of(NotificationChannel.EMAIL),
                false));
        return null;
    }

    /** Подписки на события: одна активная и одна выключенная; журнал доставок наполняется прогоном outbox. */
    private Void createSubscriptions() {
        subscriptions.create(new WebhookSubscriptionService.SubscriptionForm(
                "CRM: отзывы и новые согласия",
                "http://localhost:9",
                Set.of("consent.revoked", "consent.granted"),
                Map.of("X-Source", "in-consensu"),
                true));
        var disabled = subscriptions.create(new WebhookSubscriptionService.SubscriptionForm(
                "Хранилище документов (выключена)", "http://localhost:9", Set.of("consent.granted"), Map.of(), true));
        subscriptions.deactivate(disabled.subscription().getId());
        return null;
    }

    /**
     * Один прогон фоновых задач, чтобы журналы не были пустыми.
     *
     * <p>Адрес подписки заведомо недоступен: доставка честно проваливается, и на дашборде появляется блок
     * «Проблемы доставки webhook» — иначе его нельзя ни увидеть, ни оценить.
     */
    private Void runBackgroundOnce() {
        notificationJob.scanNow();
        dispatcher.dispatchNow();
        outbox.deliverNow();
        verifications.runNow();
        return null;
    }

    /** Пункты формы одним набором: идентификаторы снимаются один раз и дальше живут без сессии. */
    private record Items(List<UUID> all, UUID base, UUID phone, UUID email) {}

    private ConsentRegistrationService.RegistrationResult register(
            List<UUID> formItems, SubjectService.SubjectForm subject, List<UUID> acceptedItems, String key) {
        List<ConsentRegistrationService.ItemDecision> decisions = formItems.stream()
                .map(itemId -> new ConsentRegistrationService.ItemDecision(itemId, acceptedItems.contains(itemId)))
                .toList();

        return registration.register(
                "demo-showcase-" + key,
                new ConsentRegistrationService.RegistrationRequest(
                        null,
                        subject,
                        publishedFormId,
                        decisions,
                        clock.instant(),
                        ConsentSource.WEBSITE_APPLICATION,
                        "Заявка с сайта",
                        SignatureType.SIMPLE_ES_SMS,
                        Map.of(
                                // Телефон в доказательстве — в формате E.164: без «плюса» проверка состава
                                // доказательств (FR-4.2) справедливо отклоняет регистрацию.
                                "phone", "+" + subject.contacts().get(0).value().replaceAll("\\D", ""),
                                "otpVerifiedAt", clock.instant().toString(),
                                "otpHash", "demo-hash",
                                "ip", "10.0.0.2",
                                "userAgent", "Mozilla/5.0")));
    }

    private static UUID itemId(ConsentForm form, String typeCode) {
        return form.getItems().stream()
                .filter(item -> item.getConsentType().getCode().equals(typeCode))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private String today() {
        return LocalDate.now(clock).toString();
    }

    /** Кусок витрины: отказ не останавливает запуск, но и не прячется — причина уходит в лог. */
    private void step(String name, List<String> roles, java.util.function.Supplier<Void> action) {
        try {
            as(roles, action);
        } catch (RuntimeException failed) {
            LOG.warn("Витрина демо-данных: «{}» не загрузилась — {}", name, failed.getMessage());
        }
    }

    /** Демо-данные создаются от имени системы: те же проверки прав, что и у живого пользователя. */
    private <T> T as(List<String> roles, java.util.function.Supplier<T> action) {
        var authorities = roles.stream()
                .map(role -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "demo-loader", "n/a", authorities));
        try {
            return action.get();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(previous);
        }
    }
}
