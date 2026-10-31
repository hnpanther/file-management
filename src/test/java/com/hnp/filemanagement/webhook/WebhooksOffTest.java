package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhooks as they ship - off (roadmap 13.18): the dispatcher not running, a change matching a subscription queuing
 * nothing, no test event sent, and the page saying so. With no host allowed, no subscription can even be made; this
 * one is written in the database, as one made while they were on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WebhooksOffTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WebhookService webhookService;
    @Autowired
    private WebhookDispatcher dispatcher;
    @Autowired
    private WebhookRuleCache ruleCache;
    @Autowired
    private FileService fileService;
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

    @Test
    @DisplayName("off: nothing running, nothing queued, no test event, no host to subscribe to - and the page says it is off")
    void off() throws Exception {
        assertThat(dispatcher.isRunning()).isFalse();
        User person = userRepository.save(TestData.user());
        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, person);
        entityManager.flush();

        assertThatThrownBy(() -> webhookService.create(new WebhookService.Definition("off", "http://127.0.0.1/x",
                WebhookScope.SUBTREE, chain.categoryId(), null, EnumSet.of(WebhookEvent.FILE_CREATED), "", "",
                WebhookActors.ALL, "", "NONE", null, null, null, null, null), person.getId()))
                .isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("webhook.invalid.host"));

        int id = jdbc.queryForObject("""
                INSERT INTO webhook (name, url, scope, folder_id, events, signing_secret, rate_per_minute, concurrency,
                                     pause_after, created_at, created_by)
                VALUES ('made-while-on', 'http://127.0.0.1/x', 'SUBTREE', ?, 'FILE_CREATED', 'v1:x', 60, 2, 20, now(), ?)
                RETURNING id""", Integer.class, chain.categoryId(), person.getId());
        ruleCache.refresh();
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("quiet.txt");
        request.setFileNameDescription("quiet.txt");
        request.setFolderId(chain.tagId());
        request.setMultipartFile(new MockMultipartFile("file", "quiet.txt", "text/plain", TestData.bytesFor("quiet.txt")));
        fileService.createNewFile(request, person.getId(), FileService.PUBLIC);
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery WHERE webhook_id = ?", Integer.class, id)).isZero();

        assertThatThrownBy(() -> webhookService.queueTest(id, "x", person.getId()))
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("webhook.invalid.off"));

        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(person.getId());
        principal.setUsername(person.getUsername());
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(1);
        principal.setPermissions(List.of(PermissionEnum.WEBHOOKS_PAGE));
        String page = mockMvc.perform(get("/settings/webhooks").with(user(principal)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("id=\"webhooks-off\"").contains("made-while-on").contains("هیچ - تا");
    }
}
