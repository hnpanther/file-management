package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The webhook pages (roadmap 13.18, 2.19.0): each behind its permission and rendered with every message it names; a
 * subscription made - its secret on the page that made it, never in a redirect, a flash or a later page - a refusal
 * back to its form, the buttons: a new secret, pause, resume, a test event to a real receiver, the deliveries and a
 * delivery sent again, delete. What the subscriptions do is the other webhook tests'.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"filemanagement.webhooks.enabled=true", "filemanagement.webhooks.threads=0",
        "filemanagement.webhooks.allowed-hosts=127.0.0.1,localhost", "filemanagement.webhooks.timeout-seconds=2"})
class WebhookPagesTest extends DatabaseSupport {

    private static final Pattern SECRET = Pattern.compile("whsec_[A-Za-z0-9_-]{43}");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WebhookRepository webhookRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private TestReceiver receiver;
    private User person;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() throws Exception {
        receiver = new TestReceiver();
        person = userRepository.save(TestData.user());
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, person);
        entityManager.flush();
    }

    @AfterEach
    void tearDown() {
        receiver.close();
    }

    @Test
    @DisplayName("the page: its permission's; the form only with SAVE_WEBHOOK; nothing of a secret; the allowlist shown")
    void thePage() throws Exception {
        mockMvc.perform(get("/settings/webhooks").with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE))))
                .andExpect(status().isForbidden());

        String readOnly = page(get("/settings/webhooks"), PermissionEnum.WEBHOOKS_PAGE);
        assertThat(readOnly).contains("وب‌هوک‌ها").contains("127.0.0.1").doesNotContain("id=\"webhookForm\"")
                .doesNotContain("id=\"webhooks-off\"");

        String full = page(get("/settings/webhooks"), PermissionEnum.WEBHOOKS_PAGE, PermissionEnum.SAVE_WEBHOOK);
        assertThat(full).contains("id=\"webhookForm\"").contains("فایل تازه").contains("این پوشه و همهٔ زیرپوشه‌ها");
    }

    @Test
    @DisplayName("made: the secret on the page the request renders, once - not on the list, the form, a flash; a refusal back to its form")
    void madeAndItsSecret() throws Exception {
        mockMvc.perform(create("hook-without-permission", receiver.url("/x")).with(user(principal(PermissionEnum.WEBHOOKS_PAGE))))
                .andExpect(status().isForbidden());

        MvcResult made = mockMvc.perform(create("page-hook", receiver.url("/page")).param("authKind", "HEADER")
                        .param("authName", "X-Api-Key").param("authSecret", "credential-xyz")
                        .with(user(principal(PermissionEnum.SAVE_WEBHOOK))))
                .andExpect(status().isOk()).andReturn();
        assertThat(made.getFlashMap().isEmpty()).as("nothing in a flash").isTrue();
        String secretPage = made.getResponse().getContentAsString();
        Matcher secret = SECRET.matcher(secretPage);
        assertThat(secret.find()).isTrue();
        assertThat(secretPage).contains("فقط همین یک بار").doesNotContain("credential-xyz");
        entityManager.flush();
        int id = webhookRepository.findByNameIgnoreCase("page-hook").orElseThrow().getId();

        String list = page(get("/settings/webhooks"), PermissionEnum.WEBHOOKS_PAGE, PermissionEnum.SAVE_WEBHOOK);
        String form = page(get("/settings/webhooks/{id}", id), PermissionEnum.WEBHOOKS_PAGE, PermissionEnum.SAVE_WEBHOOK);
        assertThat(list).contains("page-hook").doesNotContain(secret.group()).doesNotContain("credential-xyz");
        assertThat(form).contains("page-hook").contains("X-Api-Key").contains("نگه داشته شده")
                .doesNotContain(secret.group()).doesNotContain("credential-xyz");

        String refused = mockMvc.perform(create("elsewhere", "https://n8n.example.com/webhook/a")
                        .with(user(principal(PermissionEnum.SAVE_WEBHOOK, PermissionEnum.WEBHOOKS_PAGE))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(refused).contains("n8n.example.com").contains("در فهرست نشانی‌های مجاز").contains("value=\"elsewhere\"");
        assertThat(SECRET.matcher(refused).find()).isFalse();
    }

    @Test
    @DisplayName("the buttons: a new secret shown once, pause, resume, a test event delivered and one refused, delete with its permission")
    void theButtons() throws Exception {
        int id = made("buttons", receiver.url("/buttons"));

        String rotated = mockMvc.perform(post("/settings/webhooks/{id}/rotate-secret", id).with(csrf())
                        .with(user(principal(PermissionEnum.SAVE_WEBHOOK))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(SECRET.matcher(rotated).find()).isTrue();
        assertThat(rotated).contains("یک ساعت");

        mockMvc.perform(post("/settings/webhooks/{id}/pause", id).with(csrf()).with(user(principal(PermissionEnum.SAVE_WEBHOOK))))
                .andExpect(redirectedUrl("/settings/webhooks")).andExpect(flash().attributeExists("saved"));
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT enabled FROM webhook WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(page(get("/settings/webhooks"), PermissionEnum.WEBHOOKS_PAGE)).contains("متوقف");
        mockMvc.perform(post("/settings/webhooks/{id}/resume", id).with(csrf()).with(user(principal(PermissionEnum.SAVE_WEBHOOK))))
                .andExpect(redirectedUrl("/settings/webhooks"));
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT enabled FROM webhook WHERE id = ?", Boolean.class, id)).isTrue();

        mockMvc.perform(post("/settings/webhooks/{id}/test", id).with(csrf()).with(user(principal(PermissionEnum.SAVE_WEBHOOK))))
                .andExpect(redirectedUrl("/settings/webhooks"))
                .andExpect(flash().attribute("saved", "رویداد آزمایشی رسید (پاسخ 200)."));
        assertThat(receiver.receivedAt("/buttons")).hasSize(1);
        receiver.answer("/buttons", 503);
        mockMvc.perform(post("/settings/webhooks/{id}/test", id).with(csrf()).with(user(principal(PermissionEnum.SAVE_WEBHOOK))))
                .andExpect(flash().attribute("refused", "رویداد آزمایشی نرسید (پاسخ 503): HTTP 503: answered 503"));

        mockMvc.perform(post("/settings/webhooks/{id}/delete", id).with(csrf()).with(user(principal(PermissionEnum.SAVE_WEBHOOK))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/settings/webhooks/{id}/delete", id).with(csrf()).with(user(principal(PermissionEnum.DELETE_WEBHOOK))))
                .andExpect(redirectedUrl("/settings/webhooks")).andExpect(flash().attributeExists("saved"));
        entityManager.flush();
        assertThat(webhookRepository.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("the deliveries: newest first, a page at a time; one given up sent again, or every one")
    void theDeliveries() throws Exception {
        int id = made("deliveries", receiver.url("/deliveries"));
        receiver.answer("/deliveries", 410);
        mockMvc.perform(post("/settings/webhooks/{id}/test", id).with(csrf()).with(user(principal(PermissionEnum.SAVE_WEBHOOK))));
        entityManager.flush();
        long deliveryId = jdbc.queryForObject("SELECT id FROM webhook_delivery WHERE webhook_id = ?", Long.class, id);

        String page = page(get("/settings/webhooks/{id}/deliveries", id), PermissionEnum.WEBHOOKS_PAGE, PermissionEnum.SAVE_WEBHOOK);
        assertThat(page).contains("ارسال‌های «deliveries»").contains("ناموفق").contains("HTTP 410")
                .contains("delivery-" + deliveryId).contains("ارسال دوباره");
        assertThat(page(get("/settings/webhooks/{id}/deliveries", id).param("before", "nonsense"), PermissionEnum.WEBHOOKS_PAGE))
                .contains("delivery-" + deliveryId);
        assertThat(page(get("/settings/webhooks/{id}/deliveries", id).param("before", "1_1"), PermissionEnum.WEBHOOKS_PAGE))
                .doesNotContain("delivery-" + deliveryId);

        mockMvc.perform(post("/settings/webhooks/{id}/retry", id).param("deliveryId", String.valueOf(deliveryId)).with(csrf())
                        .with(user(principal(PermissionEnum.SAVE_WEBHOOK))))
                .andExpect(redirectedUrl("/settings/webhooks/" + id + "/deliveries"))
                .andExpect(flash().attribute("saved", "1 ارسال دوباره در صف رفت."));
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT state FROM webhook_delivery WHERE id = ?", String.class, deliveryId)).isEqualTo("PENDING");

        mockMvc.perform(get("/settings/webhooks/{id}/deliveries", 999_999).with(user(principal(PermissionEnum.WEBHOOKS_PAGE))))
                .andExpect(redirectedUrl("/settings/webhooks"));
    }

    // ------------------------------------------------------------------ pieces

    private int made(String name, String url) throws Exception {
        mockMvc.perform(create(name, url).with(user(principal(PermissionEnum.SAVE_WEBHOOK)))).andExpect(status().isOk());
        entityManager.flush();
        return webhookRepository.findByNameIgnoreCase(name).orElseThrow().getId();
    }

    private MockHttpServletRequestBuilder create(String name, String url) {
        return post("/settings/webhooks").with(csrf())
                .param("name", name).param("url", url).param("scope", "SUBTREE")
                .param("folderId", String.valueOf(chain.categoryId()))
                .param("event", "FILE_CREATED").param("event", "MOVED")
                .param("extensions", "").param("namePattern", "").param("actors", "ALL")
                .param("ratePerMinute", "60").param("concurrency", "2").param("pauseAfter", "20");
    }

    private String page(MockHttpServletRequestBuilder request, PermissionEnum... permissions) throws Exception {
        return mockMvc.perform(request.with(user(principal(permissions))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(person.getId());
        principal.setUsername(person.getUsername());
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(1);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
