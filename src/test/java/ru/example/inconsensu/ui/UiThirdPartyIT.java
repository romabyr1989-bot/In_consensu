package ru.example.inconsensu.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import ru.example.inconsensu.common.domain.RoleCode;
import ru.example.inconsensu.common.domain.ThirdPartyRole;
import ru.example.inconsensu.iam.domain.AppUser;
import ru.example.inconsensu.support.AbstractIntegrationTest;
import ru.example.inconsensu.support.RunAs;
import ru.example.inconsensu.support.TestAccounts;
import ru.example.inconsensu.thirdparty.application.ThirdPartyService;
import ru.example.inconsensu.thirdparty.domain.ThirdParty;

/** Приёмка UI-11: справочник третьих лиц, вкладки карточки, договор и выгрузки партнёру. */
@AutoConfigureMockMvc
class UiThirdPartyIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private ThirdPartyService thirdParties;

    @Test
    void directory_shows_contract_term_and_russian_categories() throws Exception {
        ThirdParty party = createParty(LocalDate.now().plusDays(10));

        mockMvc.perform(get("/ui/third-parties").session(loginAs(RoleCode.MANAGER.name())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(party.getName())))
                // UI-11: в колонке договора видны и номер, и срок, и бейдж «истекает через N дней».
                .andExpect(content().string(containsString("до " + formatted(party.getContractValidUntil()))))
                .andExpect(content().string(containsString("истекает через")))
                // UI-0.4: категории ПДн названы по-русски, а не кодами справочника.
                .andExpect(content().string(containsString("Фамилия, имя, отчество")))
                .andExpect(content().string(not(containsString(">FIO,"))));
    }

    @Test
    void card_has_two_tabs_and_export_dialog_names_what_goes_into_the_file() throws Exception {
        ThirdParty party = createParty(LocalDate.now().plusYears(1));
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());

        mockMvc.perform(get("/ui/third-parties/" + party.getId()).session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Реквизиты и договор")))
                .andExpect(content().string(containsString("Выгрузки")));

        mockMvc.perform(get("/ui/third-parties/" + party.getId() + "?tab=exports")
                        .session(dpo))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Сформировать выгрузку")))
                // UI-11: диалог называет формат, число записей и категории ПДн, которые попадут в файл.
                .andExpect(content().string(containsString("Попадёт записей")))
                .andExpect(content().string(containsString("Категории ПДн в файле")))
                .andExpect(content().string(containsString("Фамилия, имя, отчество")))
                .andExpect(content().string(containsString("Выгрузок ещё не было")));
    }

    @Test
    void export_is_created_and_downloaded_through_the_interface() throws Exception {
        ThirdParty party = createParty(LocalDate.now().plusYears(1));
        MockHttpSession dpo = loginAs(RoleCode.DPO.name());

        mockMvc.perform(post("/ui/third-parties/" + party.getId() + "/exports")
                        .session(dpo)
                        .with(csrf())
                        .param("format", "csv"))
                .andExpect(status().is3xxRedirection());

        String exportsTab = mockMvc.perform(get("/ui/third-parties/" + party.getId() + "?tab=exports")
                        .session(dpo))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(exportsTab).contains("Скачать").contains("/ui/exports/");

        // UI-11: ссылка ведёт в интерфейс, а не в машинный API — там сессионная кука получала бы 401.
        String downloadUrl = exportsTab.substring(
                exportsTab.indexOf("/ui/exports/"), exportsTab.indexOf("/download") + "/download".length());
        String disposition = mockMvc.perform(get(downloadUrl).session(dpo))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("Content-Disposition");
        assertThat(disposition).contains("attachment");
    }

    @Test
    void editing_requisites_keeps_the_fields_the_form_does_not_ask_about() throws Exception {
        ThirdParty party = createParty(LocalDate.now().plusYears(1));
        MockHttpSession lawyer = loginAs(RoleCode.LAWYER.name());

        // Форма спрашивает обо всех реквизитах: без этих полей сохранение затирало бы их пустыми.
        mockMvc.perform(get("/ui/third-parties/" + party.getId()).session(lawyer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"shortName\"")))
                .andExpect(content().string(containsString("name=\"ogrn\"")))
                .andExpect(content().string(containsString("name=\"contractDate\"")));

        mockMvc.perform(post("/ui/third-parties/" + party.getId())
                        .session(lawyer)
                        .with(csrf())
                        .param("name", "ООО «Моменто» (новое имя)")
                        .param("shortName", party.getShortName())
                        .param("ogrn", party.getOgrn())
                        .param("address", party.getAddress())
                        .param("role", party.getRole().name())
                        .param("contractNumber", party.getContractNumber())
                        .param("contractDate", party.getContractDate().toString())
                        .param(
                                "contractValidUntil",
                                party.getContractValidUntil().toString())
                        .param("allowedPdnCategories", "FIO")
                        .param("contactEmail", party.getContactEmail()))
                .andExpect(status().is3xxRedirection());

        ThirdParty saved = thirdParties.get(party.getId());
        assertThat(saved.getName()).isEqualTo("ООО «Моменто» (новое имя)");
        // Форма не спрашивала о кратком наименовании, ОГРН и дате договора — и стирала их при сохранении.
        assertThat(saved.getShortName()).isEqualTo(party.getShortName());
        assertThat(saved.getOgrn()).isEqualTo(party.getOgrn());
        assertThat(saved.getContractDate()).isEqualTo(party.getContractDate());
    }

    @Test
    void requisites_are_readable_without_the_right_to_edit_them() throws Exception {
        ThirdParty party = createParty(LocalDate.now().plusYears(1));

        String page = mockMvc.perform(
                        get("/ui/third-parties/" + party.getId()).session(loginAs(RoleCode.MANAGER.name())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // §16.2: читать справочник вправе все роли; правка закрыта, а раньше была скрыта и сама карточка.
        assertThat(page).contains(party.getInn()).contains(party.getContractNumber());
        assertThat(page).doesNotContain("name=\"contractNumber\"");
    }

    /** UI-0.9: отказ сервера показывается и сводкой сверху, и подписью под полем. */
    @Test
    void server_validation_marks_the_field_that_was_rejected() throws Exception {
        MockHttpSession lawyer = loginAs(RoleCode.LAWYER.name());

        // Повторный ИНН: справочник его не примет, и ошибка обязана указать на поле.
        ThirdParty existing = createParty(LocalDate.now().plusYears(1));
        String redirect = mockMvc.perform(post("/ui/third-parties")
                        .session(lawyer)
                        .with(csrf())
                        .param("inn", existing.getInn())
                        .param("name", "ООО «Ошибка»")
                        .param("address", "Москва")
                        .param("role", ThirdPartyRole.PROCESSOR.name()))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();

        mockMvc.perform(get(redirect).session(lawyer))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("invalid-feedback")));
    }

    private String formatted(LocalDate date) {
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

    private MockHttpSession loginAs(String roleCode) throws Exception {
        AppUser user = accounts.create(roleCode);
        return (MockHttpSession)
                mockMvc.perform(formLogin("/ui/login").user(user.getLogin()).password(TestAccounts.PASSWORD))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }
}
