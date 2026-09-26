package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.ContentKindForm;
import com.hnp.filemanagement.dto.ContentProbeDTO;
import com.hnp.filemanagement.entity.ContentKind;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.validation.ContentTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.transaction.AfterTransaction;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Custom content kinds: the probe describes a sample, an added kind is recognised on upload
 * exactly like a built-in one, what can never be added, and what deleting one takes with it.
 */
@ServiceIntegrationTest
class ContentKindServiceTest extends DatabaseSupport {

    /** AutoCAD DWG: "AC10" followed by the version digits. */
    private static final byte[] DWG = "AC1027 drawing bytes".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private ContentKindService underTest;
    @Autowired
    private UploadPolicyService uploadPolicyService;
    @Autowired
    private UserRepository userRepository;

    private int adminId;

    @BeforeEach
    void setUp() {
        adminId = userRepository.save(TestData.user()).getId();
    }

    /** The registry is static and the test transaction rolls back: put the registry back to what the table says. */
    @AfterTransaction
    void resetRegistry() {
        underTest.refreshRegistry();
    }

    // ---------------------------------------------------------------- the probe

    @Test
    @DisplayName("the probe names the extension, what the bytes look like, the first bytes as hex, and whether the catalogue knows it")
    void theProbeDescribesASample() {
        ContentProbeDTO pdf = underTest.probe(new MockMultipartFile("sample", "report.pdf", "text/html", TestData.bytesFor("report.pdf")));

        assertThat(pdf.extension()).isEqualTo("pdf");
        assertThat(pdf.detectedType()).isEqualTo("application/pdf");
        assertThat(pdf.declaredType()).as("shown, never trusted").isEqualTo("text/html");
        assertThat(pdf.headHex()).startsWith("25 50 44 46 2D");
        assertThat(pdf.suggestedHex()).startsWith("2550");
        assertThat(pdf.knownAs()).isEqualTo("application/pdf");
        assertThat(pdf.bytesMatchKnown()).isTrue();
        assertThat(pdf.refused()).isNull();

        ContentProbeDTO lying = underTest.probe(new MockMultipartFile("sample", "photo.png", null, TestData.bytesFor("report.pdf")));
        assertThat(lying.knownAs()).isEqualTo("image/png");
        assertThat(lying.bytesMatchKnown()).as("PDF bytes under a .png name").isFalse();

        ContentProbeDTO unknown = underTest.probe(new MockMultipartFile("sample", "plan.dwg", null, DWG));
        assertThat(unknown.knownAs()).isNull();
        assertThat(unknown.refused()).isNull();
        assertThat(unknown.looksLikeText()).isTrue();

        ContentProbeDTO svg = underTest.probe(new MockMultipartFile("sample", "icon.svg", null, "<svg/>".getBytes()));
        assertThat(svg.refused()).contains("never");
        ContentProbeDTO bare = underTest.probe(new MockMultipartFile("sample", "noextension", null, DWG));
        assertThat(bare.refused()).contains("no extension");
    }

    // ---------------------------------------------------------------- adding

    @Test
    @DisplayName("an added kind is in the catalogue at once, recognised by its signature, and refused when the bytes differ")
    void anAddedKindIsRecognisedOnUpload() {
        ContentKind kind = underTest.add(form("dwg", "application/acad", "41 43 31 30", 0, false), adminId);

        assertThat(kind.getSignatureHex()).isEqualTo("41433130");
        assertThat(ContentTypes.knownExtensions()).contains("dwg");
        assertThat(ContentTypes.isBuiltIn("dwg")).isFalse();
        assertThat(ContentTypes.kindOf("dwg")).get().satisfies(k -> {
            assertThat(k.mediaType()).isEqualTo("application/acad");
            assertThat(k.inlineSafe()).as("a custom kind is never inline").isFalse();
            assertThat(k.matchDescription()).isEqualTo("starts with 41 43 31 30");
        });

        assertThat(ContentTypes.detect(new MockMultipartFile("f", "plan.dwg", null, DWG))).isEqualTo("application/acad");
        assertThatThrownBy(() -> ContentTypes.detect(new MockMultipartFile("f", "plan.dwg", null, "not a drawing".getBytes())))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("not a .dwg");
        assertThat(ContentTypes.servedTypeFor("DWG")).contains("application/acad");
    }

    @Test
    @DisplayName("a signature may sit at an offset, and a text-only kind needs none")
    void offsetsAndTextOnly() {
        underTest.add(form("m4a", "audio/mp4", "66747970", 4, false), adminId);
        byte[] m4a = new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};
        assertThat(ContentTypes.detect(new MockMultipartFile("f", "song.m4a", null, m4a))).isEqualTo("audio/mp4");
        assertThat(ContentTypes.kindOf("m4a")).get().extracting(ContentTypes.Kind::matchDescription).isEqualTo("at offset 4: 66 74 79 70");

        underTest.add(form("md", "text/markdown", "", 0, true), adminId);
        assertThat(ContentTypes.detect(new MockMultipartFile("f", "notes.md", null, "# title".getBytes()))).isEqualTo("text/markdown");
        assertThatThrownBy(() -> ContentTypes.detect(new MockMultipartFile("f", "notes.md", null, new byte[]{'a', 0, 'b'})))
                .as("a NUL byte is what tells a binary from text").isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("what can never be added: a built-in extension, a browser-active one, a duplicate, a malformed rule")
    void whatCannotBeAdded() {
        assertThatThrownBy(() -> underTest.add(form("pdf", "application/pdf", "2550", 0, false), adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("built-in");
        for (String active : new String[]{"html", "HTM", "svg", "xml", "js"}) {
            assertThatThrownBy(() -> underTest.add(form(active, "text/plain", "3C3F", 0, false), adminId))
                    .as(active).isInstanceOf(InvalidDataException.class).hasMessageContaining("browser");
        }
        assertThatThrownBy(() -> underTest.add(form("dwg", "application/acad", "41", 0, false), adminId))
                .as("one byte is not a signature").isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.add(form("dwg", "application/acad", "zz", 0, false), adminId))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.add(form("dwg", "not a type", "41433130", 0, false), adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("type/subtype");
        assertThatThrownBy(() -> underTest.add(form("d.w.g", "application/acad", "41433130", 0, false), adminId))
                .isInstanceOf(InvalidDataException.class);

        underTest.add(form("dwg", "application/acad", "41433130", 0, false), adminId);
        assertThatThrownBy(() -> underTest.add(form(".DWG", "application/acad", "41433130", 0, false), adminId))
                .as("case and a leading dot are normalised before the duplicate check")
                .isInstanceOf(DuplicateResourceException.class);
        assertThat(ContentTypes.knownExtensions()).doesNotContain("html", "svg", "js");
    }

    // ---------------------------------------------------------------- deleting

    @Test
    @DisplayName("deleting a kind takes it out of the catalogue and out of every upload policy that named it")
    void deletingRemovesThePolicyRulesToo() {
        underTest.add(form("dwg", "application/acad", "41433130", 0, false), adminId);
        uploadPolicyService.saveGlobal(Map.of("pdf", 5L, "dwg", 15L), adminId);
        assertThat(uploadPolicyService.globalLimits()).containsKey("dwg");

        underTest.delete("dwg", adminId);

        assertThat(ContentTypes.knownExtensions()).doesNotContain("dwg");
        assertThat(uploadPolicyService.globalLimits()).containsOnlyKeys("pdf");
        assertThat(underTest.customKinds()).isEmpty();
        assertThatThrownBy(() -> ContentTypes.detect(new MockMultipartFile("f", "plan.dwg", null, DWG)))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("not recognised");
    }

    @Test
    @DisplayName("a custom kind appears on the upload-policy rows, unticked, and can be allowed there")
    void aCustomKindIsOnThePolicyRows() {
        underTest.add(form("dwg", "application/acad", "41433130", 0, false), adminId);

        assertThat(uploadPolicyService.rowsFor(uploadPolicyService.globalLimits()))
                .filteredOn(r -> r.extension().equals("dwg")).singleElement()
                .satisfies(r -> {
                    assertThat(r.allowed()).isFalse();
                    assertThat(r.mediaType()).isEqualTo("application/acad");
                });
        uploadPolicyService.saveGlobal(Map.of("dwg", 3L), adminId);
        uploadPolicyService.requireAllowed(adminId, new MockMultipartFile("f", "plan.dwg", null, DWG));
    }

    // ---------------------------------------------------------------- helpers

    private static ContentKindForm form(String extension, String mediaType, String hex, int offset, boolean textOnly) {
        ContentKindForm form = new ContentKindForm();
        form.setExtension(extension);
        form.setMediaType(mediaType);
        form.setSignatureHex(hex);
        form.setSignatureOffset(offset);
        form.setTextOnly(textOnly);
        return form;
    }
}
