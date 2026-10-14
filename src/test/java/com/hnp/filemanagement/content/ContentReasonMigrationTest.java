package com.hnp.filemanagement.content;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3.14 (issue 120): a reason that carried the XHTML Tika answered a failed parse with - a document's
 * metadata and text - is cut back to the reason; any other reason is left as it was.
 */
@ServiceIntegrationTest
class ContentReasonMigrationTest extends DatabaseSupport {

    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("the markup after a failed parse's reason is cut off; a reason without any is untouched")
    void cutsTheMarkup() throws Exception {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        var folder = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tag();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            FileInfoDTO request = new FileInfoDTO();
            request.setDescription("d");
            request.setFileNameDescription("d");
            request.setFolderId(folder.getId());
            String name = "reason-" + TestData.nextSequence() + ".txt";
            request.setMultipartFile(new MockMultipartFile("file", name, "text/plain", name.getBytes(StandardCharsets.UTF_8)));
            FileDetailsDTO uploaded = fileService.createNewFile(request, admin.getId(), FileService.PRIVATE);
            ids.add(uploaded.getId());
        }
        entityManager.flush();
        String[] reasons = {
                "Tika could not parse the document (422): <html xmlns=\"http://www.w3.org/1999/xhtml\"> <head> <meta name=\"dc:title\" content=\"قرارداد\" />",
                "java.io.IOException: Tika's OCR container answered 500: <html><body><p>متن سند</p>",
                "OCR read no text from 1 page(s): handwriting"};
        for (int i = 0; i < 3; i++) {
            jdbc.update("UPDATE file_content SET state = 'FAILED', reason = ? WHERE file_details_id = ?", reasons[i], ids.get(i));
        }

        jdbc.execute(new ClassPathResource("db/migration/V3.14__Content_reason_without_text.sql").getContentAsString(StandardCharsets.UTF_8));

        List<String> after = new ArrayList<>();
        for (int id : ids) {
            after.add(jdbc.queryForObject("SELECT reason FROM file_content WHERE file_details_id = ?", String.class, id));
        }
        assertThat(after).containsExactly("Tika could not parse the document (422)",
                "java.io.IOException: Tika's OCR container answered 500", "OCR read no text from 1 page(s): handwriting");
    }
}
