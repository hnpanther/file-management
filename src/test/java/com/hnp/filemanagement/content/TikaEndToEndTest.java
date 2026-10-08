package com.hnp.filemanagement.content;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.support.TestTika;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading contents with the real Tika (roadmap 11.2): the image deploy/tika builds - Persian OCR, the
 * JPEG 2000 decoder - given the corpus of src/test/resources/content through the application's own
 * path: an upload, the worker, Tika, the quality check, the pages, the search. Every kind measured on
 * 2026-10-08 is here: a text PDF and one stored reversed, a scanner's garbage layer over a scan, a
 * JPEG 2000 scan, a PDF of a text page and a scanned one, slides, sheets, a letter, a photo.
 */
@SpringBootTest
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TikaEndToEndTest extends DatabaseSupport {

    private static final List<String> CORPUS = List.of("text-fa.pdf", "reversed-fa.pdf", "garbage-layer.pdf", "scan-jp2.pdf",
            "mixed.pdf", "photo.png", "slides.pptx", "sheets.xlsx", "letter.docx");

    @DynamicPropertySource
    static void tika(DynamicPropertyRegistry registry) {
        registry.add("filemanagement.content-search.enabled", () -> "true");
        registry.add("filemanagement.content-search.extraction.enabled", () -> "true");
        registry.add("filemanagement.content-search.tika.text-url", TestTika::url);
        registry.add("filemanagement.content-search.tika.ocr-url", TestTika::url);
    }

    @Autowired
    private FileService fileService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ContentSearchService search;

    private int ownerId;
    private final Map<String, FileDetailsDTO> uploaded = new HashMap<>();

    @BeforeAll
    void uploadTheCorpus() throws IOException {
        User owner = userRepository.save(TestData.user());
        ownerId = owner.getId();
        Folder folder = FolderFixture.chain(folderRepository, tagGroupRepository, owner).subCategory();
        long run = TestData.nextSequence();
        for (String name : CORPUS) {
            uploaded.put(name, upload(folder, name, run));
        }
        Instant until = Instant.now().plus(Duration.ofMinutes(5));
        for (String name : CORPUS) {
            int id = uploaded.get(name).getId();
            while (!List.of("DONE", "EMPTY", "SKIPPED", "FAILED").contains(state(id))) {
                assertThat(Instant.now()).as("%s read within 5 minutes", name).isBefore(until);
                sleep();
            }
        }
    }

    @Test
    @DisplayName("every file of the corpus read, none failed")
    void allRead() {
        for (String name : CORPUS) {
            int id = uploaded.get(name).getId();
            assertThat(state(id)).as(name + ": " + reason(id)).isEqualTo("DONE");
        }
    }

    @Test
    @DisplayName("a text PDF: each page its own words, as TEXT; a search answers with the page")
    void textPdf() {
        Map<Integer, String> pages = pages("text-fa.pdf");
        assertThat(pages).containsOnlyKeys(1, 2);
        assertThat(pages.get(1)).contains("قرارداد").doesNotContain("مناقصه");
        assertThat(pages.get(2)).contains("مناقصه");
        assertThat(sources("text-fa.pdf")).containsOnly("TEXT");
        assertThat(pagesFound("text-fa.pdf", "مناقصه")).containsExactly(2);
    }

    @Test
    @DisplayName("a PDF stored reversed: put right without OCR, found by its words")
    void reversedPdf() {
        assertThat(sources("reversed-fa.pdf")).containsOnly("TEXT_REVERSED");
        assertThat(pages("reversed-fa.pdf").get(1)).contains("قرارداد", "پیمانکار");
        assertThat(pagesFound("reversed-fa.pdf", "قرارداد پیمانکار")).containsExactly(1);
    }

    @Test
    @DisplayName("a scanner's garbage text layer: read again by OCR, both readings kept, the scanned words found")
    void garbageLayer() {
        assertThat(sources("garbage-layer.pdf")).containsOnly("BOTH");
        assertThat(pages("garbage-layer.pdf").get(1)).contains("مخزن", "انبار").contains("roi,rJr");
        assertThat(pagesFound("garbage-layer.pdf", "بازرسی مخزن")).containsExactly(1);
    }

    @Test
    @DisplayName("a JPEG 2000 scan is read - the decoder is in the image")
    void jpeg2000() {
        assertThat(sources("scan-jp2.pdf")).containsOnly("OCR");
        assertThat(pages("scan-jp2.pdf").get(1)).contains("انبار", "PMP");
        assertThat(jdbc.queryForObject("SELECT ocr_pages FROM file_content WHERE file_details_id = ?", Integer.class,
                uploaded.get("scan-jp2.pdf").getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a PDF of a text page and a scanned one: one reading, page 1 TEXT, page 2 OCR, each found on its page")
    void mixedPdf() {
        Map<Integer, String> pages = pages("mixed.pdf");
        assertThat(pages.get(1)).contains("پیمانکار");
        assertThat(pages.get(2)).contains("مخزن");
        assertThat(jdbc.queryForList("SELECT source FROM file_content_page WHERE file_details_id = ? ORDER BY page_number",
                String.class, uploaded.get("mixed.pdf").getId())).containsExactly("TEXT", "OCR");
        assertThat(pagesFound("mixed.pdf", "مخزن")).containsExactly(2);
        assertThat(pagesFound("mixed.pdf", "پیمانکار")).containsExactly(1);
    }

    @Test
    @DisplayName("slides, sheets, a letter and a photo: each by its own units")
    void officeAndImages() {
        Map<Integer, String> slides = pages("slides.pptx");
        assertThat(slides.get(1)).contains("بودجه");
        assertThat(slides.get(2)).contains("تعمیرات");
        assertThat(jdbc.queryForList("SELECT DISTINCT unit FROM file_content_page WHERE file_details_id = ?", String.class,
                uploaded.get("slides.pptx").getId())).containsOnly("SLIDE");

        assertThat(jdbc.queryForList("SELECT label FROM file_content_page WHERE file_details_id = ? ORDER BY page_number",
                String.class, uploaded.get("sheets.xlsx").getId())).containsExactly("خلاصه", "جزئیات");
        assertThat(pages("letter.docx").get(0)).contains("تمدید", "قرارداد");
        assertThat(sources("photo.png")).containsOnly("OCR");
        assertThat(pages("photo.png").get(0)).contains("انبار", "تمدید");
    }

    // ---------------------------------------------------------------- helpers

    private FileDetailsDTO upload(Folder folder, String name, long run) throws IOException {
        String type = switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        };
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("/content/" + name)) {
            bytes = in.readAllBytes();
        }
        String stored = name.replace(".", "-" + run + ".");
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(name);
        request.setFileNameDescription(name);
        request.setFolderId(folder.getId());
        request.setMultipartFile(new MockMultipartFile("file", stored, type, bytes));
        return fileService.createNewFile(request, ownerId, FileService.PRIVATE);
    }

    private Map<Integer, String> pages(String name) {
        Map<Integer, String> pages = new HashMap<>();
        jdbc.query("SELECT page_number, text FROM file_content_page WHERE file_details_id = ? ORDER BY page_number, part",
                rs -> {
                    pages.merge(rs.getInt(1), rs.getString(2), (a, b) -> a + "\n" + b);
                }, uploaded.get(name).getId());
        return pages;
    }

    private List<String> sources(String name) {
        return jdbc.queryForList("SELECT source FROM file_content_page WHERE file_details_id = ?", String.class,
                uploaded.get(name).getId());
    }

    /** The pages a search answers this file with. */
    private List<Integer> pagesFound(String name, String typed) {
        int id = uploaded.get(name).getId();
        return search.search(typed, false, 0, 200, ownerId).items().stream()
                .filter(result -> result.hit().fileDetailsId() == id).findFirst()
                .map(result -> result.pages().stream().map(ContentSearchService.PageMatch::pageNumber).toList())
                .orElse(List.of());
    }

    private String state(int id) {
        List<String> states = jdbc.queryForList("SELECT state FROM file_content WHERE file_details_id = ?", String.class, id);
        return states.isEmpty() ? null : states.getFirst();
    }

    private String reason(int id) {
        return jdbc.queryForObject("SELECT reason FROM file_content WHERE file_details_id = ?", String.class, id);
    }

    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
